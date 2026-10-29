package com.llmrag.llm_rag_backend.controller;

import com.llmrag.llm_rag_backend.service.AuthService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> request) {

        String username = request.get("username");
        String password = request.get("password");

        String token =
                authService.login(username, password);

        return ResponseEntity.ok(
                Map.of(
                        "message", "Login successful",
                        "username", username,
                        "token", token
                )
        );
    }
}