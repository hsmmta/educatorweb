package org.example.educatorweb.Service;

import org.example.educatorweb.dto.ChangePasswordRequest;
import org.example.educatorweb.dto.LoginRequest;
import org.example.educatorweb.dto.RegisterRequest;
import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.dto.UpdateProfileRequest;

public interface UserService {
    ResponseResult<?> register(RegisterRequest request);
    ResponseResult<?> login(LoginRequest request);
    ResponseResult<?> updateProfile(UpdateProfileRequest request);
    ResponseResult<?> changePassword(ChangePasswordRequest request);
}
