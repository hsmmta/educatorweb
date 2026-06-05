package com.nls.tts;
import java.io.FileOutputStream;
import java.io.IOException;

public class TestTTS {
    public static void main(String[] args) throws Exception {
        String text = "欢迎回来小丑缪屹磊，这里是个性化智能学习系统";
        byte[] audio = AliyunTTSUtils.textToSpeech(text);

        // 保存成本地音频（测试）
        try (FileOutputStream fos = new FileOutputStream("output.wav")) {
            fos.write(audio);
            System.out.println("合成完成！已生成 output.wav");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
