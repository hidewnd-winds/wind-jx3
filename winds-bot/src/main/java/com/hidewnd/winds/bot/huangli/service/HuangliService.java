package com.hidewnd.winds.bot.huangli.service;

import com.hidewnd.winds.bot.huangli.model.HuangliInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

/** 黄历查询与更新的业务接口。 */
public interface HuangliService {

    /**
     * 按服务端时钟查询当天黄历，不回退到历史记录。
     *
     * @return 当天黄历，无记录时返回 null
     */
    HuangliInfo getToday();

    /**
     * 校验并上传黄历图片，保存日期记录后发布更新事件。
     *
     * @param file 非空的 PNG、JPEG、GIF 或 WebP 图片
     * @param date 黄历日期，为 null 时使用服务端当天日期
     * @return 保存后的黄历记录
     * @throws IllegalArgumentException 文件为空、声明类型或内容签名不受支持
     * @throws IOException 读取或上传图片失败
     */
    HuangliInfo update(MultipartFile file, LocalDate date) throws IOException;
}
