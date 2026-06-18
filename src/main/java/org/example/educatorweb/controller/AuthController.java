package org.example.educatorweb.controller;

import org.example.educatorweb.dto.ChangePasswordRequest;
import org.example.educatorweb.dto.LoginRequest;
import org.example.educatorweb.dto.RegisterRequest;
import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.dto.UpdateProfileRequest;
import org.example.educatorweb.Service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")  // 开发环境允许跨域
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ResponseResult<?> register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public ResponseResult<?> login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
    }

    @PutMapping("/profile")
    public ResponseResult<?> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    @PutMapping("/password")
    public ResponseResult<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return userService.changePassword(request);
    }
}
