package com.example.neurovoice;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PlayerActivity extends AppCompatActivity implements ChapterAdapter.OnChapterClickListener {

    private static final String TAG = "PlayerActivity";
    private MediaPlayer mediaPlayer;
    private SeekBar audioSeekBar;
    private ImageButton playPauseButton;
    private ImageButton rewindButton;
    private ImageButton forwardButton;
    private Button chaptersButton;
    private TextView timeText;
    private ImageView bookCover;
    private TextView bookTitle;
    private TextView bookAuthor;
    private List<String> chapterFiles = new ArrayList<>();
    private List<String> chapterTitles = new ArrayList<>();
    private String zipFilePath;
    private String coverPath;
    private String title;
    private String author;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isSeeking = false;
    private int currentChapterIndex = 0;
    private ChapterAdapter chapterAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // Ustawienie czarnego paska stanu
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.black));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(0); // Jasne ikony
        }

        audioSeekBar = findViewById(R.id.audio_seek_bar);
        playPauseButton = findViewById(R.id.play_pause_button);
        rewindButton = findViewById(R.id.rewind_button);
        forwardButton = findViewById(R.id.forward_button);
        chaptersButton = findViewById(R.id.chapters_button);
        timeText = findViewById(R.id.time_text);
        bookCover = findViewById(R.id.book_cover);
        bookTitle = findViewById(R.id.book_title);
        bookAuthor = findViewById(R.id.book_author);

        zipFilePath = getIntent().getStringExtra("zip_file_path");
        coverPath = getIntent().getStringExtra("cover_path");
        title = getIntent().getStringExtra("title");
        author = getIntent().getStringExtra("author");
        if (zipFilePath == null || !new File(zipFilePath).exists()) {
            Toast.makeText(this, "Błąd: Plik ZIP nie znaleziony", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (coverPath != null && new File(coverPath).exists()) {
            bookCover.setImageURI(android.net.Uri.fromFile(new File(coverPath)));
            Log.d(TAG, "Book cover loaded: " + coverPath);
        } else {
            bookCover.setImageResource(R.drawable.ic_default_cover); // Use consistent placeholder
            Log.d(TAG, "Default cover loaded");
        }

        // Ustawienie tytułu i autora
        bookTitle.setText(title != null ? title : "Nieznany tytuł");
        bookAuthor.setText(author != null ? author : "Nieznany autor");
        Log.d(TAG, "Book title set: " + title + ", author set: " + author);

        loadChapters();
        if (chapterFiles.isEmpty()) {
            Toast.makeText(this, "ZIP nie zawiera plików audio lub archiwum jest uszkodzone", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize ChapterAdapter
        chapterAdapter = new ChapterAdapter(this, chapterTitles, this);
        chapterAdapter.setCurrentChapterIndex(currentChapterIndex);

        setupMediaPlayer(0);
        setupControls();
    }

    private void loadChapters() {
        try {
            ZipFile zipFile = new ZipFile(zipFilePath);
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            chapterFiles.clear();
            chapterTitles.clear();
            List<String> zipContents = new ArrayList<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                zipContents.add(entryName);
                if (!entry.isDirectory() && entryName.endsWith(".mp3")) {
                    chapterFiles.add(entryName);
                    // Wyodrębniamy tytuł rozdziału z nazwy pliku
                    String chapterTitle = entryName
                            .replace(".mp3", "")
                            .replace("_", " ")
                            .trim();
                    if (chapterTitle.isEmpty()) {
                        chapterTitle = "Rozdział " + (chapterFiles.size());
                    }
                    chapterTitles.add(chapterTitle);
                    Log.d(TAG, "Found chapter: " + entryName + ", title: " + chapterTitle);
                } else {
                    Log.d(TAG, "Skipped entry: " + entryName + " (not an MP3 file)");
                }
            }
            zipFile.close();
            Log.d(TAG, "Total chapters loaded: " + chapterFiles.size());
            Log.d(TAG, "Chapter files: " + chapterFiles.toString());
            Log.d(TAG, "Chapter titles: " + chapterTitles.toString());
            Log.d(TAG, "ZIP contents: " + zipContents.toString());
            if (chapterFiles.isEmpty()) {
                Log.e(TAG, "No MP3 files found in ZIP: " + zipFilePath);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading ZIP: " + e.getMessage(), e);
            Toast.makeText(this, "Błąd podczas odczytu ZIP: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupMediaPlayer(int chapterIndex) {
        try {
            currentChapterIndex = chapterIndex;
            File tempFile = extractChapter(chapterFiles.get(chapterIndex));
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            audioSeekBar.setMax(mediaPlayer.getDuration());
            updateTimeText(0, mediaPlayer.getDuration());

            // Aktualizacja tekstu przycisku i adaptera
            chaptersButton.setText(chapterTitles.get(currentChapterIndex));
            chapterAdapter.setCurrentChapterIndex(currentChapterIndex); // Update highlighting
            Log.d(TAG, "Chapters button updated to: " + chapterTitles.get(currentChapterIndex));

            mediaPlayer.setOnCompletionListener(mp -> {
                playPauseButton.setImageResource(R.drawable.play);
                audioSeekBar.setProgress(0);
                updateTimeText(0, mediaPlayer.getDuration());
                Log.d(TAG, "Playback completed for chapter: " + chapterFiles.get(chapterIndex));

                // Automatyczne przejście do następnego rozdziału
                if (currentChapterIndex + 1 < chapterFiles.size()) {
                    stopMediaPlayer();
                    setupMediaPlayer(currentChapterIndex + 1);
                    mediaPlayer.start();
                    playPauseButton.setImageResource(R.drawable.stop);
                    Log.d(TAG, "Automatically playing next chapter: " + chapterFiles.get(currentChapterIndex));
                } else {
                    Log.d(TAG, "No more chapters to play");
                }
            });

            updateSeekBar();
            Log.d(TAG, "MediaPlayer setup for chapter: " + chapterFiles.get(chapterIndex));
        } catch (IOException e) {
            Log.e(TAG, "Error playing chapter: " + e.getMessage(), e);
            Toast.makeText(this, "Błąd odtwarzania: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private File extractChapter(String chapterPath) throws IOException {
        File tempDir = new File(getCacheDir(), "temp_audio");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File tempFile = new File(tempDir, chapterPath.replace("/", "_"));
        if (tempFile.exists()) {
            tempFile.delete();
            Log.d(TAG, "Deleted cached file: " + tempFile.getAbsolutePath());
        }

        ZipFile zipFile = new ZipFile(zipFilePath);
        ZipEntry entry = zipFile.getEntry(chapterPath);
        if (entry == null) {
            throw new IOException("Chapter not found in ZIP: " + chapterPath);
        }
        FileOutputStream outputStream = new FileOutputStream(tempFile);
        byte[] buffer = new byte[1024];
        int bytesRead;
        java.io.InputStream inputStream = zipFile.getInputStream(entry);
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.close();
        inputStream.close();
        zipFile.close();
        Log.d(TAG, "Extracted chapter: " + tempFile.getAbsolutePath());
        return tempFile;
    }

    private void setupControls() {
        playPauseButton.setOnClickListener(v -> {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playPauseButton.setImageResource(R.drawable.play);
                Log.d(TAG, "Paused, set icon to play");
            } else {
                mediaPlayer.start();
                playPauseButton.setImageResource(R.drawable.stop);
                Log.d(TAG, "Playing, set icon to pause");
            }
        });

        rewindButton.setOnClickListener(v -> {
            int newPosition = mediaPlayer.getCurrentPosition() - 10000;
            if (newPosition < 0) newPosition = 0;
            mediaPlayer.seekTo(newPosition);
            audioSeekBar.setProgress(newPosition);
            updateTimeText(newPosition, mediaPlayer.getDuration());
            Log.d(TAG, "Rewind to: " + newPosition);
        });

        forwardButton.setOnClickListener(v -> {
            int newPosition = mediaPlayer.getCurrentPosition() + 10000;
            if (newPosition > mediaPlayer.getDuration()) newPosition = mediaPlayer.getDuration();
            mediaPlayer.seekTo(newPosition);
            audioSeekBar.setProgress(newPosition);
            updateTimeText(newPosition, mediaPlayer.getDuration());
            Log.d(TAG, "Forward to: " + newPosition);
        });

        audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mediaPlayer.seekTo(progress);
                    updateTimeText(progress, mediaPlayer.getDuration());
                    Log.d(TAG, "Seek to: " + progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
            }
        });

        chaptersButton.setOnClickListener(v -> showChaptersDialog());
    }

    private void showChaptersDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.CustomAlertDialog);
        builder.setTitle("Wybierz rozdział")
                .setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, chapterTitles) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        if (convertView == null) {
                            convertView = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
                        }

                        TextView textView = convertView.findViewById(android.R.id.text1);
                        textView.setText(getItem(position));

                        // Wyróżnienie bieżącego rozdziału
                        if (position == currentChapterIndex) {
                            textView.setTextColor(ContextCompat.getColor(getContext(), R.color.black));
                            textView.setTypeface(null, android.graphics.Typeface.BOLD);
                        } else {
                            textView.setTextColor(ContextCompat.getColor(getContext(), android.R.color.darker_gray));
                            textView.setTypeface(null, android.graphics.Typeface.NORMAL);
                        }

                        return convertView;
                    }
                }, (dialog, which) -> {
                    stopMediaPlayer();
                    setupMediaPlayer(which);
                    mediaPlayer.start();
                    playPauseButton.setImageResource(R.drawable.stop);
                    Log.d(TAG, "Selected chapter: " + chapterTitles.get(which));
                })
                .setNegativeButton("Anuluj", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // Ustawienie fokusu na bieżący rozdział
        ListView listView = dialog.getListView();
        listView.setSelection(currentChapterIndex);
        listView.setItemChecked(currentChapterIndex, true);
    }

    private void updateTimeText(int currentPosition, int duration) {
        String current = String.format("%02d:%02d", currentPosition / 1000 / 60, currentPosition / 1000 % 60);
        String total = String.format("%02d:%02d", duration / 1000 / 60, duration / 1000 % 60);
        timeText.setText(current + " / " + total);
    }

    private void updateSeekBar() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && !isSeeking) {
                    int currentPosition = mediaPlayer.getCurrentPosition();
                    audioSeekBar.setProgress(currentPosition);
                    updateTimeText(currentPosition, mediaPlayer.getDuration());
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer stopped and released");
        }
    }

    @Override
    public void onChapterClick(int position) {
        stopMediaPlayer();
        setupMediaPlayer(position);
        mediaPlayer.start();
        playPauseButton.setImageResource(R.drawable.stop);
        Log.d(TAG, "Selected chapter: " + chapterTitles.get(position));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMediaPlayer();
        handler.removeCallbacksAndMessages(null);
        File tempDir = new File(getCacheDir(), "temp_audio");
        if (tempDir.exists()) {
            for (File file : tempDir.listFiles()) {
                file.delete();
                Log.d(TAG, "Deleted temp file: " + file.getAbsolutePath());
            }
        }
    }
}