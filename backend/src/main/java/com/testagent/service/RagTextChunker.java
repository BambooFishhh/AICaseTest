package com.testagent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * v6.4: 需求文档切片工具。按 Markdown 标题与空行段落切块，
 * 保留章节标题作为切片 title，长块按标点边界二次切分并带重叠窗口。
 */
public final class RagTextChunker {

    public record Chunk(String title, String text) {}

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");

    private RagTextChunker() {
    }

    public static List<Chunk> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank() || chunkSize <= 0) {
            return List.of();
        }
        int size = Math.max(chunkSize, 200);
        int ov = Math.min(Math.max(overlap, 0), size / 2);
        List<Chunk> chunks = new ArrayList<>();
        String currentHeading = "";
        StringBuilder current = new StringBuilder();
        for (String block : splitBlocks(text)) {
            String heading = headingOf(block);
            String body = bodyOf(block);
            if (heading != null) {
                flush(chunks, current, currentHeading);
                current = new StringBuilder();
                currentHeading = heading;
            }
            if (body.isBlank()) {
                continue;
            }
            if (body.length() > size) {
                flush(chunks, current, currentHeading);
                current = new StringBuilder();
                for (String part : splitLong(body, size, ov)) {
                    chunks.add(new Chunk(currentHeading, part));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + body.length() + 2 > size) {
                flush(chunks, current, currentHeading);
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(body);
        }
        flush(chunks, current, currentHeading);
        return chunks;
    }

    private static void flush(List<Chunk> chunks, StringBuilder current, String heading) {
        if (current == null || current.length() == 0) {
            return;
        }
        chunks.add(new Chunk(heading, current.toString().trim()));
        current.setLength(0);
    }

    private static List<String> splitBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.isBlank()) {
                if (current.length() > 0) {
                    blocks.add(current.toString().trim());
                    current.setLength(0);
                }
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        if (current.length() > 0) {
            blocks.add(current.toString().trim());
        }
        return blocks;
    }

    private static String headingOf(String block) {
        String first = block.lines().findFirst().orElse("");
        Matcher m = HEADING.matcher(first);
        return m.matches() ? m.group(1).trim() : null;
    }

    private static String bodyOf(String block) {
        if (headingOf(block) == null) {
            return block;
        }
        return block.lines().skip(1).collect(Collectors.joining("\n")).trim();
    }

    private static List<String> splitLong(String body, int size, int overlap) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(body.length(), start + size);
            int cut = preferredCut(body, start, end);
            if (cut <= start) {
                cut = end;
            }
            String part = body.substring(start, cut).trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
            if (cut >= body.length()) {
                break;
            }
            start = Math.max(start + 1, cut - overlap);
        }
        return parts;
    }

    private static int preferredCut(String text, int start, int end) {
        int min = start + (end - start) / 2;
        for (String delimiter : new String[]{"。", "！", "？", "；", "\n", ".", "!", "?", ";", "，", ",", " ", "\t"}) {
            int idx = text.lastIndexOf(delimiter, end - 1);
            if (idx >= min) {
                return idx + delimiter.length();
            }
        }
        return end;
    }
}
