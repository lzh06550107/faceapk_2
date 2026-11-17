package com.punch.app.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;

import java.io.File;
import java.util.Locale;

public final class AvatarLoader {
    private AvatarLoader() {
    }

    public static void load(ImageView imageView,
                            TextView fallbackView,
                            String displayName,
                            @Nullable String source) {
        fallbackView.setText(buildAvatarText(displayName));
        fallbackView.setVisibility(TextView.VISIBLE);
        clearRequest(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(ImageView.INVISIBLE);

        Object model = buildModel(source);
        if (model == null) {
            return;
        }

        RequestManager requestManager = requestManagerFor(imageView, false);
        if (requestManager == null) {
            return;
        }

        RequestBuilder<Drawable> requestBuilder = requestManager
                .load(model)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e,
                                                Object model,
                                                Target<Drawable> target,
                                                boolean isFirstResource) {
                        fallbackView.setVisibility(TextView.VISIBLE);
                        imageView.setImageDrawable(null);
                        imageView.setVisibility(ImageView.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource,
                                                   Object model,
                                                   Target<Drawable> target,
                                                   DataSource dataSource,
                                                   boolean isFirstResource) {
                        fallbackView.setVisibility(TextView.GONE);
                        imageView.setVisibility(ImageView.VISIBLE);
                        return false;
                    }
                });
        applyModelSignature(requestBuilder, model);
        requestBuilder.into(imageView);
    }

    public static void clear(ImageView imageView) {
        clearRequest(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(ImageView.INVISIBLE);
    }

    private static void clearRequest(ImageView imageView) {
        RequestManager requestManager = requestManagerFor(imageView, true);
        if (requestManager != null) {
            requestManager.clear(imageView);
        }
    }

    @Nullable
    private static RequestManager requestManagerFor(ImageView imageView, boolean clearing) {
        Context context = imageView.getContext();
        boolean ownerDestroyed = isOwnerDestroyedOrFinishing(context);
        AvatarLoadPolicy.RequestManagerChoice choice =
                AvatarLoadPolicy.chooseRequestManager(clearing, ownerDestroyed);
        try {
            if (choice == AvatarLoadPolicy.RequestManagerChoice.VIEW) {
                return Glide.with(imageView);
            }
            if (choice == AvatarLoadPolicy.RequestManagerChoice.APPLICATION_CONTEXT) {
                Context appContext = context.getApplicationContext();
                return appContext != null ? Glide.with(appContext) : null;
            }
        } catch (IllegalArgumentException ignored) {
            if (clearing) {
                Context appContext = context.getApplicationContext();
                return appContext != null ? Glide.with(appContext) : null;
            }
        }
        return null;
    }

    private static boolean isOwnerDestroyedOrFinishing(Context context) {
        Activity activity = findActivity(context);
        return activity != null && (activity.isFinishing() || activity.isDestroyed());
    }

    @Nullable
    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            Context baseContext = ((ContextWrapper) current).getBaseContext();
            if (baseContext == null || baseContext == current) {
                return null;
            }
            current = baseContext;
        }
        return null;
    }

    private static Object buildModel(@Nullable String rawSource) {
        if (rawSource == null) {
            return null;
        }
        String source = rawSource.trim();
        if (source.isEmpty()) {
            return null;
        }
        if (source.startsWith("content://") || source.startsWith("file://")) {
            return Uri.parse(source);
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return buildGlideUrl(source);
        }

        File file = new File(source);
        if (file.exists() && file.isFile()) {
            return file;
        }
        if (AvatarLoadPolicy.isAndroidLocalAbsolutePath(source)) {
            return null;
        }

        String baseUrl = SessionManager.get().getBaseUrl();
        if (source.startsWith("/")) {
            return buildGlideUrl(baseUrl + source);
        }
        return buildGlideUrl(baseUrl + "/" + source);
    }

    private static void applyModelSignature(RequestBuilder<Drawable> requestBuilder, Object model) {
        if (!(model instanceof File)) {
            return;
        }
        File file = (File) model;
        requestBuilder.apply(new RequestOptions()
                .signature(new ObjectKey(file.getAbsolutePath()
                        + "_" + file.lastModified()
                        + "_" + file.length())));
    }

    private static GlideUrl buildGlideUrl(String url) {
        LazyHeaders.Builder headers = new LazyHeaders.Builder()
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId());
        String token = SessionManager.get().getToken();
        if (token != null && !token.trim().isEmpty()) {
            headers.addHeader("Authorization", "Bearer " + token.trim());
        }
        return new GlideUrl(url, headers.build());
    }

    private static String buildAvatarText(String name) {
        if (name == null) {
            return "?";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.getDefault());
    }
}
