/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.AudioSpecificConfig
import org.mp4parser.boxes.iso14496.part12.ChunkOffsetBox
import org.mp4parser.boxes.iso14496.part12.SampleSizeBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.SampleToChunkBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox
import org.mp4parser.boxes.sampleentry.AudioSampleEntry
import java.io.File
import java.io.RandomAccessFile

/**
 * The AAC frames of an ordinary (non-fragmented) .m4a file, for [AacSource].
 *
 * Java Sound's AAC plugin was tried first and misread these files outright - wrong rate, one
 * channel, a few hundred bytes of output. The YouTube path already decodes AAC correctly from frames
 * it cuts out itself, so local M4A does the same: the sample table says where every frame is (chunk
 * offsets, frames per chunk, frame sizes), and the frames are read straight from the file.
 */
internal object M4aReader {

    fun isM4a(file: File) = file.extension.lowercase() in setOf("m4a", "mp4", "m4b")

    fun open(file: File): AacSource {
        IsoFile(file.absolutePath).use { iso ->
            for (track in iso.movieBox.getBoxes(TrackBox::class.java)) {
                val stbl = track.mediaBox?.mediaInformationBox?.getBoxes(SampleTableBox::class.java)?.firstOrNull()
                    ?: continue
                val config = audioConfig(stbl) ?: continue
                return AacSource(frames(file, stbl), config)
            }
        }
        error("no AAC audio track in ${file.name}")
    }

    private fun audioConfig(stbl: SampleTableBox): ByteArray? {
        for (entry in stbl.sampleDescriptionBox.boxes) {
            if (entry !is AudioSampleEntry) continue
            val esds = entry.getBoxes(ESDescriptorBox::class.java).firstOrNull() ?: continue
            val config: AudioSpecificConfig = esds.esDescriptor?.decoderConfigDescriptor?.audioSpecificInfo ?: continue
            return config.configBytes
        }
        return null
    }

    private fun frames(file: File, stbl: SampleTableBox): List<ByteArray> {
        val sizes = stbl.getBoxes(SampleSizeBox::class.java).first()
        val offsets = stbl.getBoxes(ChunkOffsetBox::class.java).first().chunkOffsets
        val runs = stbl.getBoxes(SampleToChunkBox::class.java).first().entries
        val count = sizes.sampleCount.toInt()
        val frames = ArrayList<ByteArray>(count)
        RandomAccessFile(file, "r").use { raf ->
            var sample = 0
            for (chunk in offsets.indices) {
                // The run covering this chunk is the last one whose first chunk is at or before it
                // (chunks are numbered from one in the box).
                val perChunk = runs.lastOrNull { it.firstChunk <= chunk + 1 }?.samplesPerChunk?.toInt() ?: continue
                var position = offsets[chunk]
                repeat(perChunk) {
                    if (sample >= count) return@repeat
                    val size = sizes.getSampleSizeAtIndex(sample).toInt()
                    val bytes = ByteArray(size)
                    raf.seek(position)
                    raf.readFully(bytes)
                    frames += bytes
                    position += size
                    sample++
                }
            }
        }
        return frames
    }
}
