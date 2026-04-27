package com.capstone.backend.global.util

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtUtil(
    @Value("\${jwt.secret}") private val secretString: String,
    @Value("\${jwt.access-expiration}") private val accessExp: Long
) {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secretString.toByteArray())

    fun generateAccessToken(id: Long,email: String): String {
        val now = Date()
        val validity = Date(now.time + accessExp)

        return Jwts.builder()
            .claim("email",email)
            .subject(id.toString())
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact()
    }
    fun getIdFromToken(token: String): Long {
        val subject = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
            .subject

        return subject.toLong()
    }
    fun getEmailFromToken(token: String): String? {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
            .get("email",String::class.java)
    }
    fun validateToken(token: String): Boolean {
        return try{
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
            true
        }catch(e: Exception){
            false
        }
    }
}