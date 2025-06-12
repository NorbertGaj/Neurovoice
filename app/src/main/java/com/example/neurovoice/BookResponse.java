package com.example.neurovoice;

import com.google.gson.annotations.SerializedName;

public class BookResponse {
    @SerializedName("zip_file")
    String zipFile; // base64-encode

    @SerializedName("metadata")
    Metadata metadata;

    static class Metadata {
        @SerializedName("title")
        String title;

        @SerializedName("author")
        String author;

        @SerializedName("cover")
        String cover; // base64-encode
    }
}