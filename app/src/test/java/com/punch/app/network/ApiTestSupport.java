package com.punch.app.network;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import com.punch.app.utils.SessionManager;

import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

abstract class ApiTestSupport {
    protected static final String TEST_DEVICE_ID = "A1B2C3D4E5F60789";
    protected RecordingInterceptor interceptor;

    @Before
    public void setUpApiTestSupport() throws Exception {
        setSessionPreferences(new MemorySharedPreferences(), new MemorySharedPreferences());
        SessionManager.get().clearAll();
        putStoredDeviceId(TEST_DEVICE_ID);

        interceptor = new RecordingInterceptor();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build();

        ApiClient.resetForTest();
        ApiClient.setClientForTest(client);
        ApiClient.setBaseUrlForTest("http://localhost");
    }

    @After
    public void tearDownApiTestSupport() {
        ApiClient.resetForTest();
        SessionManager.get().clearAll();
    }

    protected String successEnvelope(String dataJson) {
        return "{" +
                "\"code\":200," +
                "\"msg\":\"success\"," +
                "\"data\":" + dataJson +
                "}";
    }

    protected String afterSingleRequestBody(String expectedPath) {
        Request request = interceptor.takeRequest();
        assertEquals(expectedPath, request.url().encodedPath());
        return interceptor.takeBody();
    }

    private void setSessionPreferences(SharedPreferences prefs, SharedPreferences securePrefs) throws Exception {
        Field prefsField = SessionManager.class.getDeclaredField("prefs");
        prefsField.setAccessible(true);
        prefsField.set(SessionManager.get(), prefs);

        Field securePrefsField = SessionManager.class.getDeclaredField("securePrefs");
        securePrefsField.setAccessible(true);
        securePrefsField.set(SessionManager.get(), securePrefs);
    }

    private void putStoredDeviceId(String deviceId) throws Exception {
        Field prefsField = SessionManager.class.getDeclaredField("prefs");
        prefsField.setAccessible(true);
        SharedPreferences prefs = (SharedPreferences) prefsField.get(SessionManager.get());
        prefs.edit().putString("device_id", deviceId).apply();
    }

    protected static final class RecordingInterceptor implements Interceptor {
        private final Deque<QueuedResponse> responses = new ArrayDeque<>();
        private final List<String> requestBodies = new ArrayList<>();
        private Request lastRequest;
        private String lastBody = "";
        private int requestCount;

        void enqueueJson(int httpCode, String body) {
            responses.addLast(new QueuedResponse(httpCode, body, null));
        }

        void enqueueFailure(String message) {
            responses.addLast(new QueuedResponse(0, "", new IOException(message)));
        }

        int getRequestCount() {
            return requestCount;
        }

        String getRequestBody(int index) {
            return requestBodies.get(index);
        }

        Request takeRequest() {
            return lastRequest;
        }

        String takeBody() {
            return lastBody;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            lastRequest = chain.request();
            if (lastRequest.body() != null) {
                okio.Buffer buffer = new okio.Buffer();
                lastRequest.body().writeTo(buffer);
                lastBody = buffer.readUtf8();
            } else {
                lastBody = "";
            }
            requestBodies.add(lastBody);

            QueuedResponse queued = responses.removeFirst();
            if (queued.failure != null) {
                throw queued.failure;
            }
            ResponseBody responseBody = ResponseBody.create(
                    queued.body,
                    MediaType.parse("application/json; charset=utf-8")
            );
            return new Response.Builder()
                    .request(lastRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(queued.httpCode)
                    .message("OK")
                    .body(responseBody)
                    .build();
        }
    }

    private static final class QueuedResponse {
        final int httpCode;
        final String body;
        final IOException failure;

        QueuedResponse(int httpCode, String body, IOException failure) {
            this.httpCode = httpCode;
            this.body = body;
            this.failure = failure;
        }
    }

    private static final class MemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, new HashSet<>(values));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                pending.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                pending.clear();
                removals.clear();
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) {
                    values.clear();
                }
                for (String key : removals) {
                    values.remove(key);
                }
                values.putAll(pending);
            }
        }
    }
}
