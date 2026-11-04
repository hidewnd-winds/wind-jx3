package com.hidewnd.winds.bot.huangli.service.impl;

import com.hidewnd.winds.bot.huangli.event.HuangliUpdatedEvent;
import com.hidewnd.winds.bot.huangli.model.HuangliInfo;
import com.hidewnd.winds.bot.huangli.repository.HuangliRepository;
import com.hidewnd.winds.bot.huangli.service.HuangliService;
import com.hidewnd.winds.bot.oss.ObjectStorage;
import com.hidewnd.winds.common.base.utils.ImageUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;

/** 黄历图片存储、按日期查询及更新事件发布的业务实现。 */
@Service
public class HuangliServiceImpl implements HuangliService {

    private final ObjectStorage objectStorage;
    private final HuangliRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public HuangliServiceImpl(
            ObjectStorage objectStorage,
            HuangliRepository repository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.objectStorage = objectStorage;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public HuangliInfo getToday() {
        return repository.findById(LocalDate.now(clock).toString()).orElse(null);
    }

    @Override
    public HuangliInfo update(MultipartFile file, LocalDate date) throws IOException {
        if (file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new IllegalArgumentException("仅支持上传图片");
        }
        String imageExtension;
        try (InputStream input = file.getInputStream()) {
            imageExtension = ImageUtils.detectImageExtension(input);
        }
        if (imageExtension == null) {
            throw new IllegalArgumentException("仅支持 PNG、JPEG、GIF 或 WebP 图片");
        }

        LocalDate targetDate = date == null ? LocalDate.now(clock) : date;
        String formattedDate = targetDate.toString();
        String objectName = "huangli/" + formattedDate + imageExtension;
        String url = objectStorage.upload(file, objectName);
        HuangliInfo saved = repository.save(new HuangliInfo(formattedDate, url));
        eventPublisher.publishEvent(new HuangliUpdatedEvent(saved.getDate(), saved.getUrl()));
        return saved;
    }
}
