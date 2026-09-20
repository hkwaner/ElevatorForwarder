package org.example.demo2.alarm;

import org.example.demo2.Config;
import org.example.demo2.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 电梯报警上报:通过 HTTP POST 上报到平台报警接口,异步发送不阻塞调用线程。
 */
public class ElevatorAlarmReporter {
    private static final Logger log = LoggerFactory.getLogger(ElevatorAlarmReporter.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private ElevatorAlarmReporter() {
    }

    /**
     * 上报电梯报警
     *
     * @param type    报警类型
     * @param content 报警完整自定义内容(模板只配一个 %s,平台用本值填充;传空则走模板原样)
     */
    public static void report(ElevatorAlarmType type, String... content) {
        // 报警路径必须绝对安全:任何异常都不能冒泡到电梯处理线程,否则会中断电梯状态广播
        try {
            if (Config.PLATFORM_BASE_URL == null || Config.PLATFORM_BASE_URL.trim().isEmpty()
                    || Config.ALARM_ROBOT_ID == null || Config.ALARM_ROBOT_ID.trim().isEmpty()) {
                log.info("[报警] 上报跳过:未配置平台地址或机器人ID");
                return;
            }
            List<String> analysis = new ArrayList<>();
            for (String c : content) {
                analysis.add(c);
            }
            AlarmSMSMessageDingTalkEmailSend request = new AlarmSMSMessageDingTalkEmailSend();
            request.setRobotId(Config.ALARM_ROBOT_ID);
            request.setAlarmTypeId(type.getTypeId());
            request.setIsRegain(type.isRegain());
            request.setAlarmAnalysis(analysis);
            request.setAlarmObj(0);

            String url = Config.PLATFORM_BASE_URL + AlarmSMSMessageDingTalkEmailSend.URL;
            String json = JsonUtils.getGson().toJson(request);
            RequestBody body = RequestBody.create(JSON, json);
            Request httpRequest = new Request.Builder().url(url).post(body).build();

            client.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.info("[报警] 上报失败 type:{} err:{}", type, e.toString());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody rb = response.body()) {
                        String resp = rb == null ? "" : rb.string();
                        if (response.isSuccessful()) {
                            log.info("[报警] 上报成功 type:{} resp:{}", type, resp);
                        } else {
                            log.info("[报警] 上报失败 type:{} code:{} resp:{}", type, response.code(), resp);
                        }
                    }
                }
            });
        } catch (Exception e) {
            // 平台地址配置非法等异常:只记录日志,绝不影响电梯处理线程
            log.info("[报警] 上报异常 type:{} err:{}", type, e.toString());
        }
    }
}
