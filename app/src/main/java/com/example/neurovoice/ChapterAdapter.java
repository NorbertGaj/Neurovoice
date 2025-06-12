package com.example.neurovoice;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder> {

    private List<String> chapters;
    private OnChapterClickListener listener;
    private Context context;
    private int currentChapterIndex = -1;

    public interface OnChapterClickListener {
        void onChapterClick(int position);
    }

    public ChapterAdapter(Context context, List<String> chapters, OnChapterClickListener listener) {
        this.context = context;
        this.chapters = chapters;
        this.listener = listener;
    }


    public void setCurrentChapterIndex(int index) {
        this.currentChapterIndex = index;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChapterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false);
        return new ChapterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChapterViewHolder holder, int position) {
        String chapter = chapters.get(position);
        holder.title.setText(chapter);

        if (position == currentChapterIndex) {
            holder.title.setTextColor(ContextCompat.getColor(context, R.color.black));
            holder.title.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            holder.title.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray));
            holder.title.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        holder.itemView.setOnClickListener(v -> listener.onChapterClick(position));
    }

    @Override
    public int getItemCount() {
        return chapters.size();
    }

    static class ChapterViewHolder extends RecyclerView.ViewHolder {
        TextView title;

        ChapterViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.chapter_title);
        }
    }
}