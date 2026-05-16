package com.readaccess.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;

public class ConfigStore {

    private static final Path DIR = Paths.get(System.getProperty("user.home"), ".readaccess");
    private static final Path DB_PATH  = DIR.resolve("db-config.json");
    private static final Path API_PATH = DIR.resolve("api-config.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void save(DbConfig config) {
        write(DB_PATH, GSON.toJson(config));
    }

    public static DbConfig load() {
        return read(DB_PATH, DbConfig.class, new DbConfig());
    }

    public static void saveApi(ApiConfig config) {
        write(API_PATH, GSON.toJson(config));
    }

    public static ApiConfig loadApi() {
        return read(API_PATH, ApiConfig.class, new ApiConfig());
    }

    private static void write(Path path, String json) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(path, json);
        } catch (IOException e) {
            System.err.println("Failed to save " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private static <T> T read(Path path, Class<T> type, T fallback) {
        if (!Files.exists(path)) return fallback;
        try {
            return GSON.fromJson(Files.readString(path), type);
        } catch (IOException e) {
            System.err.println("Failed to load " + path.getFileName() + ": " + e.getMessage());
            return fallback;
        }
    }
}
