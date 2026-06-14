package com.nls.tts;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TokenManager {
    private static final String REGIONID = "cn-shanghai";
    private static final String DOMAIN = "nls-meta.cn-shanghai.aliyuncs.com";

    private static final String API_VERSION = "2019-02-28";
    private static final String REQUEST_ACTION = "CreateToken";
    private static final String KEY_TOKEN = "Token";
    private static final String KEY_ID = "Id";
    private static final String KEY_EXPIRETIME = "ExpireTime";

    private static final String accessKeyId = System.getenv("ALIYUN_AK_ID");
    private static final String accessKeySecret = System.getenv("ALIYUN_AK_SECRET");

    private static final String TOKEN_FILE = "token_info.txt";
    private static final long REFRESH_BEFORE_SEC = 300;
    public static String getValidToken() {
        File file = new File(TOKEN_FILE);
        if(file.exists()){
            try(BufferedReader br = new BufferedReader(new FileReader(file))){
                // 格式：token=xxx;expire=17xxxxxx
                String line = br.readLine();
                if(line != null && line.contains("token=") && line.contains("expire=")){
                    String token = line.split("token=")[1].split(";")[0];
                    long expireSec = Long.parseLong(line.split("expire=")[1]);
                    long now = System.currentTimeMillis()/1000;
                    // 没到提前刷新时间，直接返回
                    if(now < expireSec - REFRESH_BEFORE_SEC){
                        System.out.println("使用本地缓存Token");
                        return token;
                    }
                }
            }catch (Exception e){
                System.out.println("本地文件读取异常，重新拉取token");
            }
        }
        return refreshAndSaveToken();
    }

    private static String refreshAndSaveToken(){
        try {
            DefaultProfile profile = DefaultProfile.getProfile(REGIONID, accessKeyId, accessKeySecret);
            IAcsClient client = new DefaultAcsClient(profile);
            CommonRequest request = new CommonRequest();
            request.setDomain(DOMAIN);
            request.setVersion(API_VERSION);
            request.setAction(REQUEST_ACTION);
            request.setMethod(MethodType.POST);
            request.setProtocol(ProtocolType.HTTPS);
            CommonResponse response = client.getCommonResponse(request);
            System.out.println("阿里云返回原始数据："+response.getData());

            if (response.getHttpStatus() == 200) {
                JSONObject result = JSON.parseObject(response.getData());
                String token = result.getJSONObject(KEY_TOKEN).getString(KEY_ID);
                long expireTime = result.getJSONObject(KEY_TOKEN).getLongValue(KEY_EXPIRETIME);
                String expireDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expireTime * 1000));
                System.out.println("新Token："+token+" 过期时间："+expireDate);

                // 写入txt：token=xxx;expire=时间戳
                try(FileWriter fw = new FileWriter(TOKEN_FILE)){
                    fw.write("token="+token+";expire="+expireTime);
                }
                return token;
            }else {
                throw new RuntimeException("阿里云获取Token失败，http非200");
            }
        } catch (ClientException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        String token = getValidToken();
        System.out.println("最终可用token："+token);
    }
}