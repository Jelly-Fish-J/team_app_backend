package com.example.app_backend.manager.statistics

import com.example.app_backend.manager.redis.RedisService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class StatisticsController(private val redisService: RedisService) {

    @GetMapping("/redis-data")
    fun getRedisData(): List<RedisData> {
        return redisService.getAllData()
    }

}