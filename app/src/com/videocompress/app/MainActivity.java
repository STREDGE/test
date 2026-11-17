package com.videocompress.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK = 1;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MB = 1000L * 1000L;

    private Button pickButton;
    private Button startButton;
    private Button cancelButton;
    private EditText targetField;
    private CheckBox h264Box;
    private TextView infoText;
    private TextView planText;
    private TextView statusText;
    private ProgressBar progressBar;
    private View resultRow;

    private Uri sourceUri;
    private VideoInfo info;
    private Plan plan;
    private Uri resultUri;
    private boolean busy;
    private final AtomicBoolean cancel = new AtomicBoolean();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        pickButton = findViewById(R.id.pick);
        startButton = findViewById(R.id.start);
        cancelButton = findViewById(R.id.cancel);
        targetField = findViewById(R.id.target);
        h264Box = findViewById(R.id.h264);
        infoText = findViewById(R.id.info);
        planText = findViewById(R.id.plan);
        statusText = findViewById(R.id.status);
        progressBar = findViewById(R.id.progress);
        resultRow = findViewById(R.id.result);

        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("video/*");
                startActivityForResult(intent, REQUEST_PICK);
            }
        });
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCompression();
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancel.set(true);
                statusText.setText("Останавливаю…");
            }
        });
        findViewById(R.id.open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(resultUri, "video/mp4");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch(intent);
            }
        });
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("video/mp4");
                intent.putExtra(Intent.EXTRA_STREAM, resultUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch(Intent.createChooser(intent, "Поделиться видео"));
            }
        });
        targetField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updatePlan();
            }
        });
        h264Box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updatePlan();
            }
        });

        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onDestroy() {
        // The encoding thread must not outlive the screen it reports to.
        cancel.set(true);
        super.onDestroy();
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        if (busy) {
            toast("Дождитесь окончания текущего сжатия");
            return;
        }
        loadVideo(uri);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadVideo(data.getData());
        }
    }

    private void loadVideo(final Uri uri) {
        sourceUri = uri;
        info = null;
        plan = null;
        resultUri = null;
        resultRow.setVisibility(View.GONE);
        statusText.setText("");
        planText.setText("");
        infoText.setText("Читаю видео…");
        startButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                VideoInfo loaded = null;
                String error = null;
                try {
                    loaded = VideoInfo.analyze(MainActivity.this, uri);
                } catch (Exception e) {
                    error = describe(e);
                }
                final VideoInfo result = loaded;
                final String message = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!uri.equals(sourceUri)) return;
                        if (result == null) {
                            infoText.setText("Не удалось прочитать видео: " + message);
                            return;
                        }
                        info = result;
                        boolean sideways = info.rotation == 90 || info.rotation == 270;
                        infoText.setText(String.format(Locale.US, "%s\n%s · %s · %d×%d · %d кадр/с%s",
                                info.name, megabytes(info.fileBytes), duration(info.durationSec()),
                                sideways ? info.height : info.width, sideways ? info.width : info.height,
                                info.fps, info.hdr ? " · HDR" : ""));
                        updatePlan();
                    }
                });
            }
        }, "analyze").start();
    }

    private long targetBytes() {
        try {
            return Long.parseLong(targetField.getText().toString().trim()) * MB;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Plan makePlan(String mime) {
        return Plan.make(info.fileBytes, info.audioBytes, info.durationSec(), info.width, info.height,
                info.fps, info.videoBitrate(), targetBytes(), mime);
    }

    private boolean canEncode(String mime, int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null;
    }

    private void updatePlan() {
        if (info == null || busy) return;
        plan = null;
        startButton.setEnabled(false);
        if (targetBytes() <= 0) {
            planText.setText("Укажите размер на выходе в мегабайтах.");
            return;
        }
        Plan p = h264Box.isChecked() ? makePlan(Plan.AVC) : makePlan(Plan.HEVC);
        if (p.error == null && Plan.HEVC.equals(p.mime) && !canEncode(Plan.HEVC, p.width, p.height)) {
            p = makePlan(Plan.AVC);
        }
        if (p.error != null) {
            planText.setText(p.error);
            return;
        }
        plan = p;
        startButton.setEnabled(true);

        StringBuilder text = new StringBuilder();
        if (p.alreadyFits) {
            text.append("Видео уже меньше заданного размера — сжимать не обязательно.\n");
        }
        boolean sideways = info.rotation == 90 || info.rotation == 270;
        text.append(String.format(Locale.US, "Будет: %s, %d×%d, %.1f Мбит/с\n",
                Plan.HEVC.equals(p.mime) ? "HEVC (H.265)" : "H.264",
                sideways ? p.height : p.width, sideways ? p.width : p.height, p.videoBitrate / 1e6));
        if (p.quality == Plan.QUALITY_GREAT) {
            text.append("Качество: отличное, разницу с оригиналом на глаз не видно.");
        } else if (p.quality == Plan.QUALITY_GOOD) {
            text.append("Качество: хорошее, мелкие детали станут чуть мягче.");
        } else {
            text.append("Качество: заметно ниже оригинала — видео слишком длинное для такого размера.");
        }
        if (info.audioTrack >= 0 && !info.audioCopyable) {
            text.append("\nВнимание: звук в формате ").append(info.audioMime)
                    .append(" не поддерживается и в результат не попадёт.");
        }
        planText.setText(text);
    }

    private void setBusy(boolean value) {
        busy = value;
        pickButton.setEnabled(!value);
        targetField.setEnabled(!value);
        h264Box.setEnabled(!value);
        startButton.setEnabled(!value && plan != null);
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void startCompression() {
        if (busy || plan == null || info == null) return;
        final Uri uri = sourceUri;
        final VideoInfo video = info;
        final Plan firstPlan = plan;
        final long target = targetBytes();
        cancel.set(false);
        resultUri = null;
        resultRow.setVisibility(View.GONE);
        progressBar.setProgress(0);
        statusText.setText("Сжимаю… Не закрывайте приложение.");
        setBusy(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File temp = new File(getCacheDir(), "out.mp4");
                String message;
                Uri saved = null;
                try {
                    Plan current = firstPlan;
                    int bitrate = current.videoBitrate;
                    for (int attempt = 1; ; attempt++) {
                        final int shownAttempt = attempt;
                        final boolean[] producedFrames = {false};
                        try {
                            Transcoder.transcode(MainActivity.this, uri, temp, video, current, bitrate, cancel,
                                    new Transcoder.Progress() {
                                        private int last = -1;

                                        @Override
                                        public void onProgress(float fraction) {
                                            producedFrames[0] = true;
                                            final int value = (int) (fraction * 1000);
                                            if (value == last) return;
                                            last = value;
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    progressBar.setProgress(value);
                                                    statusText.setText(String.format(Locale.US,
                                                            "Сжимаю… %d%%%s  Не закрывайте приложение.", value / 10,
                                                            shownAttempt > 1 ? " (проход " + shownAttempt + ")" : ""));
                                                }
                                            });
                                        }
                                    });
                        } catch (Transcoder.Cancelled e) {
                            throw e;
                        } catch (Exception e) {
                            // Some phones advertise HEVC but refuse the actual settings.
                            if (Plan.HEVC.equals(current.mime) && !producedFrames[0]) {
                                Plan fallback = Plan.make(video.fileBytes, video.audioBytes, video.durationSec(),
                                        video.width, video.height, video.fps, video.videoBitrate(), target, Plan.AVC);
                                if (fallback.error == null) {
                                    current = fallback;
                                    bitrate = fallback.videoBitrate;
                                    attempt--;
                                    continue;
                                }
                            }
                            throw e;
                        }
                        long size = temp.length();
                        if (size <= target || attempt >= MAX_ATTEMPTS) break;
                        // The encoder overshot: lower the bitrate in proportion and go again.
                        bitrate = (int) (bitrate * ((double) target / size) * 0.95);
                    }

                    long size = temp.length();
                    saved = saveToGallery(temp, video.name);
                    message = String.format(Locale.US, "Готово: %s → %s\nСохранено в «Фильмы/VideoCompressor».%s",
                            megabytes(video.fileBytes), megabytes(size),
                            size > target ? "\nУложиться в заданный размер не удалось — попробуйте ещё раз "
                                    + "с меньшим значением." : "");
                } catch (Transcoder.Cancelled e) {
                    message = "Отменено.";
                } catch (Exception e) {
                    message = "Ошибка: " + describe(e);
                } finally {
                    temp.delete();
                }

                final String shown = message;
                final Uri result = saved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        resultUri = result;
                        setBusy(false);
                        statusText.setText(shown);
                        resultRow.setVisibility(result != null ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }, "compress").start();
    }

    private Uri saveToGallery(File file, String sourceName) throws Exception {
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, base + "_compressed.mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoCompressor");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) throw new Exception("Не удалось создать файл в галерее");
        try {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("Не удалось открыть файл в галерее для записи");
                byte[] chunk = new byte[1 << 16];
                int n;
                while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            toast("Нет приложения, которое может это открыть");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f МБ", bytes / (double) MB);
    }

    private static String duration(double seconds) {
        long total = Math.round(seconds);
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }
}
