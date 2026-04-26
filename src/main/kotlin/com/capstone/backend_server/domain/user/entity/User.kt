package com.capstone.backend_server.domain.user.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User (
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true, length = 255)
    val email: String,

    @Column(nullable = false, length= 50)
    var nickname: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE"
){
    fun updateNickname(newNickname: String){
        this.nickname = newNickname
    }

}