package org.example.demo2.alarm;

import org.example.demo2.Config;
import org.example.demo2.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 电梯报警独立测试入口(仅供开发自测,不参与生产流程)。
 * <p>
 * 单独触发某一路报警,用于验证平台模板/接收是否正常,无需等真实卡住。
 * 用法:
 * java ElevatorAlarmTest stuck   触发卡住报警(T_00140)
 * java ElevatorAlarmTest recover 触发恢复报警(RT_00140)
 */
public class ElevatorAlarmTest {
    private static final Logger log = LoggerFactory.getLogger(ElevatorAlarmTest.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) throws Exception {
        // 确保配置就绪
        if (Config.PLATFORM_BASE_URL == null || Config.PLATFORM_BASE_URL.trim().isEmpty()
                || Config.ALARM_ROBOT_ID == null || Config.ALARM_ROBOT_ID.trim().isEmpty()) {
            log.error("[报警测试] 未配置平台地址或机器人ID,请先检查 Config");
            return;
        }

        String mode = "recover";
        String url = Config.PLATFORM_BASE_URL + AlarmSMSMessageDingTalkEmailSend.URL;

        if (mode.equals("recover")) {
            // 恢复报警:RT_00140(按普通报警 isRegain=false 上报,与生产逻辑一致)
            AlarmSMSMessageDingTalkEmailSend req = new AlarmSMSMessageDingTalkEmailSend();
            req.setRobotId(Config.ALARM_ROBOT_ID);
            req.setAlarmTypeId("RT_00140");
            req.setIsRegain(false);
            req.setAlarmAnalysis(Collections.singletonList("电梯已恢复,到达目标5层"));
            post(url, req, "恢复报警(RT_00140)");
        } else if (mode.equals("stuck")){
            // 卡住报警:T_00140
            AlarmSMSMessageDingTalkEmailSend req = new AlarmSMSMessageDingTalkEmailSend();
            req.setRobotId(Config.ALARM_ROBOT_ID);
            req.setAlarmTypeId("T_00140");
            req.setIsRegain(false);
            req.setAlarmAnalysis(Collections.singletonList("电梯卡在3层,目标5层,正在自动处理"));
            req.setAlarmObj(0);
            post(url, req, "卡住报警(T_00140)");
        }
    }

    /** 同步发送,等待平台响应 */
    private static void post(String url, AlarmSMSMessageDingTalkEmailSend req, String name) throws Exception {
        String json = JsonUtils.getGson().toJson(req);
        log.info("[报警测试] 发送{} url:{} body:{}", name, url, json);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, json))
                .build();
        Call call = client.newCall(request);
        try (Response resp = call.execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            log.info("[报警测试] {} 完成 code:{} resp:{}", name, resp.code(), body);
            // 平台接口无论成败都返回200,是否成功需看平台侧的报警日志
        }
    }
}