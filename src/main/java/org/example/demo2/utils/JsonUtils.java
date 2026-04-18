package org.example.demo2.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class JsonUtils {
    public static Gson getGson() {
        return (new GsonBuilder()).setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").serializeNulls().enableComplexMapKeySerialization().serializeSpecialFloatingPointValues().create();
    }
}
