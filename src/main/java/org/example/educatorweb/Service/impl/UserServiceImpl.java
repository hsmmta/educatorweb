package org.example.educatorweb.Service.impl;

import org.example.educatorweb.dto.ChangePasswordRequest;
import org.example.educatorweb.dto.LoginRequest;
import org.example.educatorweb.dto.RegisterRequest;
import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.dto.UpdateProfileRequest;
import org.example.educatorweb.entity.User;
import org.example.educatorweb.repository.UserRepository;
import org.example.educatorweb.Service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public ResponseResult<?> register(RegisterRequest request) {
        if (userRepository.existsByPhone(request.getPhone())) {
            return ResponseResult.error("手机号已注册");
        }
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            if (userRepository.existsByEmail(request.getEmail())) {
                return ResponseResult.error("邮箱已被使用");
            }
        }

        User user = new User();
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        userRepository.save(user);
        return ResponseResult.success("注册成功");
    }

    @Override
    public ResponseResult<?> login(LoginRequest request) {
        User user = userRepository.findByPhone(request.getPhone()).orElse(null);
        if (user == null) {
            return ResponseResult.error("手机号未注册");
        }

        // 验证码登录模式：跳过密码校验（验证码由前端/短信服务校验）
        if ("code".equals(request.getLoginMode())) {
            user.setPasswordHash(null);
            return ResponseResult.success(user);
        }

        // 密码登录模式
        if (request.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return ResponseResult.error("手机号或密码错误");
        }
        user.setPasswordHash(null);
        return ResponseResult.success(user);
    }

    @Override
    public ResponseResult<?> updateProfile(UpdateProfileRequest request) {
        User user = userRepository.findByPhone(request.getPhone()).orElse(null);
        if (user == null) {
            return ResponseResult.error("用户不存在");
        }

        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            user.setNickname(request.getNickname());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            if (!request.getEmail().equals(user.getEmail())
                    && userRepository.existsByEmail(request.getEmail())) {
                return ResponseResult.error("邮箱已被其他账号使用");
            }
            user.setEmail(request.getEmail());
        }

        userRepository.save(user);
        user.setPasswordHash(null);
        log.info("用户资料已更新: phone={}", request.getPhone());
        return ResponseResult.success(user);
    }

    @Override
    public ResponseResult<?> changePassword(ChangePasswordRequest request) {
        User user = userRepository.findByPhone(request.getPhone()).orElse(null);
        if (user == null) {
            return ResponseResult.error("用户不存在");
        }

        // 验证码校验由前端/短信服务完成，此处直接更新密码
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("密码已修改: phone={}", request.getPhone());
        return ResponseResult.success("密码修改成功");
    }
}
