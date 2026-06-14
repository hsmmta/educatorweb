package com.nls.tts;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class AliyunTTSUtils {
    private static final String APP_KEY = System.getenv("APP_KEY");
    private static NlsClient nlsClient;
    static {
        nlsClient = new NlsClient(TokenManager.getValidToken());
    }

    public static byte[] textToSpeech(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        if (text.length() > 300) {
            throw new IllegalArgumentException("文本过长，当前长度：" + text.length() + "，请控制在 300 字以内");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        SpeechSynthesizerListener listener = new SpeechSynthesizerListener() {
            @Override
            public void onComplete(SpeechSynthesizerResponse response) {}

            @Override
            public void onMessage(ByteBuffer message) {
                byte[] buf = new byte[message.remaining()];
                message.get(buf);
                try {
                    outputStream.write(buf);
                } catch (Exception ignored) {}
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                throw new RuntimeException("合成失败：" + response.getStatusText());
            }

            @Override
            public void onMetaInfo(SpeechSynthesizerResponse response) {}
        };

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(nlsClient, listener);
        synthesizer.setAppKey(APP_KEY);
        synthesizer.setFormat(OutputFormatEnum.WAV);
        synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
        synthesizer.setVoice("siyue");
        synthesizer.setText(text);

        synthesizer.start();
        synthesizer.waitForComplete();
        synthesizer.close();

        return outputStream.toByteArray();
    }
}
