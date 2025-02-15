/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commands.uinput;

import android.util.Log;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;

/**
 * Uinput class encapsulates execution of "uinput" command. It parses the provided input stream
 * parameters as JSON file format, extract event entries and perform commands of event entries.
 * Uinput device will be created when performing registration command and used to inject events.
 */
public class Uinput {
    private static final String TAG = "UINPUT";

    private final EventParser mParser;
    private final SparseArray<Device> mDevices;

    private static void usage() {
        error("Usage: uinput [FILE]");
    }

    /**
     * Commandline "uinput" binary main entry
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            usage();
            System.exit(1);
        }

        InputStream stream = null;
        try {
            if (args[0].equals("-")) {
                stream = System.in;
            } else {
                File f = new File(args[0]);
                stream = new FileInputStream(f);
            }
            (new Uinput(stream)).run();
        } catch (EvemuParser.ParsingException e) {
            System.err.println(e.makeErrorMessage());
            error(e.makeErrorMessage(), e);
            System.exit(1);
        } catch (Exception e) {
            error("Uinput injection failed.", e);
            System.exit(1);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
            }
        }
    }

    private Uinput(InputStream in) {
        mDevices = new SparseArray<Device>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            mParser = isEvemuFile(reader) ? new EvemuParser(reader) : new JsonStyleParser(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isEvemuFile(BufferedReader in) throws IOException {
        // After zero or more empty lines (not even containing horizontal whitespace), evemu
        // recordings must either start with '#' (indicating the EVEMU version header or a comment)
        // or 'N' (for the name line). If we encounter anything else, assume it's a JSON-style input
        // file.

        String lineSep = System.lineSeparator();
        char[] buf = new char[1];

        in.mark(1 /* readAheadLimit */);
        int charsRead = in.read(buf);
        while (charsRead > 0 && lineSep.contains(String.valueOf(buf[0]))) {
            in.mark(1 /* readAheadLimit */);
            charsRead = in.read(buf);
        }
        in.reset();
        return buf[0] == '#' || buf[0] == 'N';
    }

    private void run() {
        try {
            Event e = null;
            while ((e = mParser.getNextEvent()) != null) {
                process(e);
            }
        } catch (IOException ex) {
            error("Error reading in events.", ex);
        }

        for (int i = 0; i < mDevices.size(); i++) {
            mDevices.valueAt(i).close();
        }
    }

    private void process(Event e) {
        final int index = mDevices.indexOfKey(e.getId());
        if (index < 0) {
            if (e.getCommand() != Event.Command.REGISTER) {
                Log.e(TAG, "Unknown device id specified. Ignoring event.");
                return;
            }
            registerDevice(e);
            return;
        }

        final Device d = mDevices.valueAt(index);
        switch (Objects.requireNonNull(e.getCommand())) {
            case REGISTER ->
                    error("Device id=" + e.getId() + " is already registered. Ignoring event.");
            case INJECT -> d.injectEvent(e.getInjections());
            case DELAY -> d.addDelay(e.getDurationMillis());
            case SYNC -> d.syncEvent(e.getSyncToken());
        }
    }

    private void registerDevice(Event e) {
        if (!Event.Command.REGISTER.equals(e.getCommand())) {
            throw new IllegalStateException(
                    "Tried to send command \"" + e.getCommand() + "\" to an unregistered device!");
        }
        int id = e.getId();
        Device d = new Device(id, e.getName(), e.getVendorId(), e.getProductId(),
                e.getVersionId(), e.getBus(), e.getConfiguration(), e.getFfEffectsMax(),
                e.getAbsInfo(), e.getPort());
        mDevices.append(id, d);
    }

    private static void error(String msg) {
        error(msg, null);
    }

    private static void error(String msg, Exception e) {
        Log.e(TAG, msg);
        if (e != null) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}
