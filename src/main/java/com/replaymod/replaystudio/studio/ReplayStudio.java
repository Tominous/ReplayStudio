/*
 * This file is part of ReplayStudio, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016 johni0702 <https://github.com/johni0702>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.replaymod.replaystudio.studio;

import com.github.steveice10.mc.protocol.packet.ingame.server.ServerKeepAlivePacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginSuccessPacket;
import com.github.steveice10.packetlib.packet.Packet;
import com.google.gson.Gson;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.Studio;
import com.replaymod.replaystudio.collection.PacketList;
import com.replaymod.replaystudio.collection.ReplayPart;
import com.replaymod.replaystudio.filter.Filter;
import com.replaymod.replaystudio.filter.SquashFilter;
import com.replaymod.replaystudio.filter.StreamFilter;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.replay.Replay;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import com.replaymod.replaystudio.stream.PacketStream;
import com.replaymod.replaystudio.util.Utils;
import com.replaymod.replaystudio.viaversion.ViaVersionPacketConverter;

//#if MC>=10800
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerSetCompressionPacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginSetCompressionPacket;
//#endif

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ReplayStudio implements Studio {

    private static final Gson GSON = new Gson();

    private final Set<Class<? extends Packet>> shouldBeParsed = Collections.newSetFromMap(new HashMap<>());

    /**
     * Whether packets should be wrapped instead of parsed unless they're set to be parsed explicitly.
     */
    private boolean wrappingEnabled = true;

    @SuppressWarnings("deprecation")
    private final ServiceLoader<Filter> filterServiceLoader = ServiceLoader.load(Filter.class);
    private final ServiceLoader<StreamFilter> streamFilterServiceLoader = ServiceLoader.load(StreamFilter.class);

    public ReplayStudio() {
        // Required for importing / exporting
        //#if MC>=10800
        setParsing(LoginSetCompressionPacket.class, true);
        setParsing(ServerSetCompressionPacket.class, true);
        //#endif
        setParsing(ServerKeepAlivePacket.class, true);
        setParsing(LoginSuccessPacket.class, true);
    }

    @Override
    public String getName() {
        return "ReplayStudio";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    @Deprecated
    public Filter loadFilter(String name) {
        for (Filter filter : filterServiceLoader) {
            if (filter.getName().equalsIgnoreCase(name)) {
                try {
                    // Create a new instance of the filter
                    return filter.getClass().newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return null;
    }

    @Override
    public StreamFilter loadStreamFilter(String name) {
        for (StreamFilter filter : streamFilterServiceLoader) {
            if (filter.getName().equalsIgnoreCase(name)) {
                try {
                    // Create a new instance of the filter
                    return filter.getClass().newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return null;
    }

    @Override
    @Deprecated
    public ReplayPart squash(ReplayPart part) {
        part = part.copy();
        new SquashFilter().apply(part);
        return part;
    }

    @Override
    @Deprecated
    public ReplayPart createReplayPart() {
        return new StudioReplayPart(new PacketList());
    }

    @Override
    @Deprecated
    public ReplayPart createReplayPart(Collection<PacketData> packets) {
        return new StudioReplayPart(new PacketList(packets));
    }

    @Override
    @Deprecated
    public Replay createReplay(InputStream in) throws IOException {
        return createReplay(in, false);
    }

    @Override
    @Deprecated
    public Replay createReplay(ReplayFile file) throws IOException {
        return new StudioReplay(this, file);
    }

    @Override
    @Deprecated
    public Replay createReplay(InputStream in, int fileFormatVersion) throws IOException {
        return new ReplayInputStream(this, in, fileFormatVersion).toReplay();
    }

    @Override
    @Deprecated
    public Replay createReplay(InputStream in, int fileFormatVersion, int fileProtocol) throws IOException {
        return new ReplayInputStream(this, in, fileFormatVersion, fileProtocol).toReplay();
    }

    @Override
    @Deprecated
    public Replay createReplay(InputStream in, boolean raw) throws IOException {
        if (raw) {
            return new StudioReplay(this, in);
        } else {
            Replay replay = null;
            ReplayMetaData meta = null;
            try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in))) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    if ("metaData.json".equals(entry.getName())) {
                        meta = GSON.fromJson(new InputStreamReader(Utils.notCloseable(zipIn)), ReplayMetaData.class);
                    }
                    if ("recording.tmcpr".equals(entry.getName())) {
                        replay = new StudioReplay(this, Utils.notCloseable(zipIn));
                    }
                }
            }
            if (replay != null) {
                if (meta != null) {
                    replay.setMetaData(meta);
                }
                return replay;
            } else {
                throw new IOException("ZipInputStream did not contain \"recording.tmcpr\"");
            }
        }
    }

    @Override
    @Deprecated
    public ReplayMetaData readReplayMetaData(InputStream in) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if ("metaData.json".equals(entry.getName())) {
                    return GSON.fromJson(new InputStreamReader(zipIn), ReplayMetaData.class);
                }
            }
            throw new IOException("ZipInputStream did not contain \"metaData.json\"");
        }
    }

    @Override
    @Deprecated
    public Replay createReplay(ReplayPart part) {
        return new StudioDelegatingReplay(this, part);
    }

    @Override
    @Deprecated
    public PacketStream createReplayStream(InputStream in, boolean raw) throws IOException {
        if (raw) {
            return new StudioPacketStream(this, in);
        } else {
            ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(in));
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if ("recording.tmcpr".equals(entry.getName())) {
                    return new StudioPacketStream(this, zipIn);
                }
            }
            throw new IOException("ZipInputStream did not contain \"recording.tmcpr\"");
        }
    }

    @Override
    public void setParsing(Class<? extends Packet> packetClass, boolean parse) {
        if (parse) {
            shouldBeParsed.add(packetClass);
        } else {
            shouldBeParsed.remove(packetClass);
        }
    }

    @Override
    public boolean willBeParsed(Class<? extends Packet> packetClass) {
        return shouldBeParsed.contains(packetClass);
    }

    public boolean isWrappingEnabled() {
        return this.wrappingEnabled;
    }

    public void setWrappingEnabled(boolean wrappingEnabled) {
        this.wrappingEnabled = wrappingEnabled;
    }

    @Override
    @Deprecated
    public boolean isCompatible(int fileVersion) {
        return ViaVersionPacketConverter.isFileVersionSupported(fileVersion, getCurrentFileFormatVersion());
    }

    @Override
    public boolean isCompatible(int fileVersion, int protocolVersion) {
        return ViaVersionPacketConverter.isFileVersionSupported(fileVersion, protocolVersion, ReplayMetaData.CURRENT_PROTOCOL_VERSION);
    }

    @Override
    public int getCurrentFileFormatVersion() {
        return ReplayMetaData.CURRENT_FILE_FORMAT_VERSION;
    }
}
