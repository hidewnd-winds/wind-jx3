package com.hidewnd.winds.jx3.parser;

import com.hidewnd.winds.jx3.model.GameServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按官方制表符格式解析主服和别名，同一地址只保留一个探测目标。 */
public final class ServerListParser {
    private ServerListParser() {}

    public static List<GameServer> parse(String text) {
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        boolean hasServer = false;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length < 12
                    || !fields[3].matches("\\d{1,3}(?:\\.\\d{1,3}){3}")
                    || !fields[4].matches("\\d+")) {
                throw new IllegalArgumentException("区服清单格式变化");
            }
            int port = Integer.parseInt(fields[4]);
            if (port < 1 || port > 65535 || fields[9].isBlank() || fields[10].isBlank()) {
                throw new IllegalArgumentException("区服地址或标识无效");
            }
            hasServer = true;
            // 在地址合并前排除专用服，避免其成为同地址主服或混入普通服别名。
            if (GameServer.excludedFromMonitoring(fields[11], fields[10])) {
                continue;
            }
            groups.computeIfAbsent(fields[3] + ":" + fields[4], ignored -> new ArrayList<>())
                    .add(fields);
        }
        if (!hasServer) {
            throw new IllegalArgumentException("区服清单为空");
        }
        List<GameServer> result = new ArrayList<>();
        for (var rows : groups.values()) {
            String[] row = rows.getFirst();
            List<String> aliases =
                    rows.stream()
                            .map(r -> r[1])
                            .filter(name -> !name.equals(row[10]))
                            .distinct()
                            .sorted()
                            .toList();
            result.add(
                    new GameServer(
                            row[9], row[11], row[10], aliases, row[3], Integer.parseInt(row[4])));
        }
        return List.copyOf(result);
    }
}
