package com.filebridge.shared.model;

public record FileEntry(String name, boolean directory, long size, long lastModified) {}
