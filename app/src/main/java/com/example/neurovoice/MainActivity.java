package com.example.neurovoice;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public class MainActivity extends AppCompatActivity implements BookAdapter.OnBookClickListener {

    private static final String TAG = "MainActivity";
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> coverPickerLauncher;
    private final String SERVER_URL = "http://192.168.8.178:5000";
    private ProgressBar progressBar;
    private TextView statusText;
    private RecyclerView booksRecyclerView;
    private BookAdapter bookAdapter;
    private List<Book> books;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "BookPrefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ImageButton addBookBtn = findViewById(R.id.add_book_btn);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        booksRecyclerView = findViewById(R.id.books_recycler_view);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        books = loadBooks();
        bookAdapter = new BookAdapter(this, books, this);
        booksRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        booksRecyclerView.setAdapter(bookAdapter);

        addBookBtn.setOnClickListener(v -> openFilePicker());

        filePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri fileUri = result.getData().getData();
                if (fileUri != null) {
                    uploadFile(fileUri);
                }
            }
        });

        coverPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri coverUri = result.getData().getData();
                if (coverUri != null) {
                    saveCoverImage(coverUri);
                }
            }
        });
    }

    private List<Book> loadBooks() {
        List<Book> bookList = new ArrayList<>();
        File directory = new File(getFilesDir(), "audiobooks");
        if (directory.exists()) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".zip"));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    String title = prefs.getString("title_" + fileName, fileName.replace(".zip", ""));
                    String author = prefs.getString("author_" + fileName, "Unknown");
                    String coverPath = prefs.getString("cover_" + fileName, null);
                    bookList.add(new Book(fileName, title, author, coverPath));
                    Log.d(TAG, "Loaded book: " + fileName + ", title: " + title + ", author: " + author);
                }
            }
        }
        return bookList;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/x-fictionbook+xml", "text/plain"});
        filePickerLauncher.launch(intent);
    }

    private void uploadFile(Uri fileUri) {
        try {
            statusText.setText("Wysłanie pliku...");
            statusText.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            progressBar.setVisibility(View.VISIBLE);

            String filePath = FileUtil.getPath(this, fileUri);
            if (filePath == null) {
                showError("Nie udało się pobrać pliku");
                return;
            }
            File file = new File(filePath);
            Log.d(TAG, "Uploading file: " + file.getAbsolutePath() + ", size: " + file.length());

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.HOURS)
                    .writeTimeout(30, TimeUnit.HOURS)
                    .build();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(SERVER_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            ApiService apiService = retrofit.create(ApiService.class);
            RequestBody requestFile = RequestBody.create(MediaType.parse("multipart/form-data"), file);
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), requestFile);

            Call<BookResponse> call = apiService.uploadFile(body);
            call.enqueue(new Callback<BookResponse>() {
                @Override
                public void onResponse(Call<BookResponse> call, Response<BookResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        Log.d(TAG, "File uploaded successfully, processing response...");
                        statusText.setText("Pobieranie audiobooku...");
                        downloadFile(response.body(), file.getName());
                    } else {
                        String errorBody = "";
                        try {
                            if (response.errorBody() != null) {
                                errorBody = response.errorBody().string();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        showError("Błąd podczas przetwarzania: " + response.code() + ", " + errorBody);
                        Log.e(TAG, "Upload failed: " + response.code() + ", " + errorBody);
                    }
                }

                @Override
                public void onFailure(Call<BookResponse> call, Throwable t) {
                    showError("Błąd sieci: " + t.getMessage());
                    Log.e(TAG, "Network error: ", t);
                }
            });
        } catch (Exception e) {
            showError("Błąd: " + e.getMessage());
            Log.e(TAG, "Upload exception: ", e);
        }
    }

    private void downloadFile(BookResponse response, String originalFileName) {
        try {
            File directory = new File(getFilesDir(), "audiobooks");
            if (!directory.exists()) {
                directory.mkdirs();
            }
            String baseName = originalFileName.replaceFirst("\\.(epub|fb2|txt)$", "");
            String zipFileName = baseName + "_" + UUID.randomUUID().toString() + ".zip";
            File zipFile = new File(directory, zipFileName);
            Log.d(TAG, "Saving ZIP to: " + zipFile.getAbsolutePath());

            // Dekodujemy base64 do pliku ZIP
            byte[] zipBytes = Base64.decode(response.zipFile, Base64.DEFAULT);
            FileOutputStream outputStream = new FileOutputStream(zipFile);
            outputStream.write(zipBytes);
            outputStream.flush();
            outputStream.close();

            // Przetwarzamy okładkę
            String coverPath = null;
            if (response.metadata.cover != null && !response.metadata.cover.isEmpty()) {
                File coverDir = new File(getFilesDir(), "covers");
                if (!coverDir.exists()) {
                    coverDir.mkdirs();
                }
                String coverFileName = "cover_" + zipFileName.replace(".zip", ".jpg");
                File coverFile = new File(coverDir, coverFileName);
                byte[] coverBytes = Base64.decode(response.metadata.cover, Base64.DEFAULT);
                FileOutputStream coverOutputStream = new FileOutputStream(coverFile);
                coverOutputStream.write(coverBytes);
                coverOutputStream.flush();
                coverOutputStream.close();
                coverPath = coverFile.getAbsolutePath();
                Log.d(TAG, "Cover saved to: " + coverPath);
            } else {
                Log.w(TAG, "No cover received for book: " + zipFileName);
            }

            progressBar.setVisibility(View.GONE);
            statusText.setVisibility(View.GONE);
            Toast.makeText(this, "Audiobook zapisany: " + zipFile.getName(), Toast.LENGTH_SHORT).show();
            Log.d(TAG, "File downloaded and saved: " + zipFile.getAbsolutePath());

            // Tytuł pochodzi z baseName (bez UUID), autor zawsze "Nieznany"
            String title = baseName; 
            String author = "Nieznany";

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("title_" + zipFileName, title);
            editor.putString("author_" + zipFileName, author);
            if (coverPath != null) {
                editor.putString("cover_" + zipFileName, coverPath);
            }
            editor.apply();

            for (int i = 0; i < books.size(); i++) {
                if (books.get(i).getFileName().equals(zipFileName)) {
                    books.remove(i);
                    break;
                }
            }
            books.add(new Book(zipFileName, title, author, coverPath));
            bookAdapter.notifyDataSetChanged();
            Log.d(TAG, "Book added: " + zipFileName + ", title: " + title + ", author: " + author);

        } catch (IOException e) {
            showError("Błąd podczas pobierania: " + e.getMessage());
            Log.e(TAG, "Download exception: ", e);
        }
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBookClick(Book book, int position) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("zip_file_path", getFilesDir() + "/audiobooks/" + book.getFileName());
        intent.putExtra("cover_path", book.getCoverPath());
        intent.putExtra("title", book.getTitle());
        intent.putExtra("author", book.getAuthor());
        startActivity(intent);
        Log.d(TAG, "Opening PlayerActivity for book: " + book.getFileName());
    }

    @Override
    public void onBookLongClick(Book book, int position) {
        new AlertDialog.Builder(this)
                .setTitle(book.getTitle())
                .setItems(new String[]{"Usuń", "Zmień tytuł", "Zmień autora", "Zmień okładkę"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            deleteBook(book, position);
                            break;
                        case 1:
                            editBookTitle(book, position);
                            break;
                        case 2:
                            editBookAuthor(book, position);
                            break;
                        case 3:
                            selectCoverImage(book, position);
                            break;
                    }
                })
                .show();
    }

    private void deleteBook(Book book, int position) {
        File file = new File(getFilesDir() + "/audiobooks/" + book.getFileName());
        if (file.exists() && file.delete()) {
            // Usuwamy okładkę, jeśli istnieje
            String coverPath = prefs.getString("cover_" + book.getFileName(), null);
            if (coverPath != null) {
                File coverFile = new File(coverPath);
                if (coverFile.exists()) {
                    coverFile.delete();
                }
            }
            books.remove(position);
            bookAdapter.notifyItemRemoved(position);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove("title_" + book.getFileName());
            editor.remove("author_" + book.getFileName());
            editor.remove("cover_" + book.getFileName());
            editor.apply();
            Toast.makeText(this, "Książka usunięta", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Book deleted: " + book.getFileName());
        } else {
            Toast.makeText(this, "Błąd podczas usuwania", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Failed to delete book: " + book.getFileName());
        }
    }

    private void editBookTitle(Book book, int position) {
        new AlertDialog.Builder(this)
                .setTitle("Zmień tytuł")
                .setView(R.layout.dialog_edit_text)
                .setPositiveButton("Zapisz", (dialog, which) -> {
                    TextView input = ((AlertDialog) dialog).findViewById(R.id.edit_text_input);
                    if (input != null && !input.getText().toString().trim().isEmpty()) {
                        String newTitle = input.getText().toString().trim();
                        book.setTitle(newTitle);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("title_" + book.getFileName(), newTitle);
                        editor.apply();
                        bookAdapter.notifyItemChanged(position);
                        Log.d(TAG, "Book title updated: " + newTitle);
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void editBookAuthor(Book book, int position) {
        new AlertDialog.Builder(this)
                .setTitle("Zmień autora")
                .setView(R.layout.dialog_edit_text)
                .setPositiveButton("Zapisz", (dialog, which) -> {
                    TextView input = ((AlertDialog) dialog).findViewById(R.id.edit_text_input);
                    if (input != null && !input.getText().toString().trim().isEmpty()) {
                        String newAuthor = input.getText().toString().trim();
                        book.setAuthor(newAuthor);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("author_" + book.getFileName(), newAuthor);
                        editor.apply();
                        bookAdapter.notifyItemChanged(position);
                        Log.d(TAG, "Book author updated: " + newAuthor);
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void selectCoverImage(Book book, int position) {
        currentBookPosition = position;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        coverPickerLauncher.launch(intent);
    }

    private int currentBookPosition = -1;

    private void saveCoverImage(Uri coverUri) {
        try {
            File directory = new File(getFilesDir(), "covers");
            if (!directory.exists()) {
                directory.mkdirs();
            }
            String coverFileName = "cover_" + books.get(currentBookPosition).getFileName().replace(".zip", ".jpg");
            File coverFile = new File(directory, coverFileName);

            InputStream inputStream = getContentResolver().openInputStream(coverUri);
            FileOutputStream outputStream = new FileOutputStream(coverFile);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();
            inputStream.close();

            books.get(currentBookPosition).setCoverPath(coverFile.getAbsolutePath());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("cover_" + books.get(currentBookPosition).getFileName(), coverFile.getAbsolutePath());
            editor.apply();
            bookAdapter.notifyItemChanged(currentBookPosition);
            Log.d(TAG, "Cover updated for book: " + books.get(currentBookPosition).getFileName());
        } catch (IOException e) {
            Toast.makeText(this, "Błąd podczas zapisywania okładki", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Cover save exception: ", e);
        }
    }

    interface ApiService {
        @Multipart
        @POST("/upload")
        Call<BookResponse> uploadFile(@Part MultipartBody.Part file);
    }
}