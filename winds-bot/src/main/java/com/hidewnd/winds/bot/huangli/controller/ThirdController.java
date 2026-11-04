package com.hidewnd.winds.bot.huangli.controller;

import com.hidewnd.winds.bot.huangli.model.HuangliInfo;
import com.hidewnd.winds.bot.huangli.service.HuangliService;
import com.hidewnd.winds.common.base.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

@Tag(name = "三方服务协作")
@RestController
@RequestMapping("/thire")
public class ThirdController {

    private final HuangliService huangliService;

    public ThirdController(HuangliService huangliService) {
        this.huangliService = huangliService;
    }

    @Operation(summary = "查询当日剑三黄历", description = "无当日数据时 obj 为 null")
    @GetMapping("/huangli/now")
    public R<HuangliInfo> getToday() {
        return R.successByObj(huangliService.getToday());
    }

    @Operation(summary = "更新剑三黄历")
    @PostMapping(value = "/huangli/update", consumes = "multipart/form-data")
    public R<HuangliInfo> update(
            @RequestParam("file") @Parameter(name = "图片资源") MultipartFile file,
            @RequestParam(name = "date", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
            @Parameter(name = "格式化日期", description = "格式：YYYY-MM-DD，为空默认今日") LocalDate date) throws IOException {
        return R.successByObj(huangliService.update(file, date));
    }
}
