package com.hidewnd.winds.scout.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.hidewnd.winds.scout.event.WeiboAlertEvent;

/** 发生时快照与当前故障状态分开，避免将历史补发误认为新故障。 */
public record WeiboAlertResponse(@JsonUnwrapped WeiboAlertEvent alert,
                                 String currentState, String resolvedAt) {
}
