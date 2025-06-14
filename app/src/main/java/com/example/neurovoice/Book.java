package com.example.neurovoice;

public class Book {
    private String fileName;
    private String title;
    private String author;
    private String coverPath;

    public Book(String fileName, String title, String author, String coverPath) {
        this.fileName = fileName;
        this.title = title;
        this.author = author;
        this.coverPath = coverPath;
    }

    public String getFileName() {
        return fileName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCoverPath() {
        return coverPath;
    }

    public void setCoverPath(String coverPath) {
        this.coverPath = coverPath;
    }
}