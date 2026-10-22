package com.feng.springailearning.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import org.springframework.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    @Tool(description = "获取指定城市的模拟天气，仅用于演示，不是真实实时天气")
    public String getCurrentWeather(
            @ToolParam(description = "城市名称，例如：北京、上海、广州") String city) {
        Assert.hasText(city, "城市名称不能为空");
        String normalizedCity = city.trim();
        Assert.isTrue(normalizedCity.length() <= 100 && normalizedCity.chars().noneMatch(Character::isISOControl),
                "城市名称过长或包含控制字符");
        log.info("weather.tool.called");

        return "【模拟数据】" + switch (normalizedCity) {
            case "北京" -> "北京 当前天气：晴朗，温度 18°C，湿度 45%";
            case "上海" -> "上海 当前天气：多云，温度 20°C，湿度 60%";
            case "广州" -> "广州 当前天气：小雨，温度 24°C，湿度 80%";
            default -> normalizedCity + " 当前天气：晴，温度 22°C";
        };
    }

    @Tool(description = "获取当前系统时间")
    public String getCurrentTime() {
        return "当前系统时间：" + LocalDateTime.now();
    }
}
