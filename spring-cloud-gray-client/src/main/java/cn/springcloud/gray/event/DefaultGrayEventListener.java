package cn.springcloud.gray.event;

import cn.springcloud.gray.CommunicableGrayManager;
import cn.springcloud.gray.GrayClientHolder;
import cn.springcloud.gray.local.InstanceLocalInfo;
import cn.springcloud.gray.local.InstanceLocalInfoAware;
import cn.springcloud.gray.exceptions.EventException;
import cn.springcloud.gray.model.GrayInstance;
import cn.springcloud.gray.model.GrayTrackDefinition;
import cn.springcloud.gray.request.track.CommunicableGrayTrackHolder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 灰度事件监听器，处理灰度管控端发来的事件消息。
 * 事件源分两种：灰度实例的，或灰度追踪的。
 * 事件类型分两种：更新、删除
 */
public class DefaultGrayEventListener implements GrayEventListener, InstanceLocalInfoAware {
    private static final Logger log = LoggerFactory.getLogger(DefaultGrayEventListener.class);

    private CommunicableGrayManager grayManager;
    private CommunicableGrayTrackHolder grayTrackHolder;
    private InstanceLocalInfo instanceLocalInfo;

    private Map<SourceType, Consumer<GrayEventMsg>> handers = new HashMap<>();

    public DefaultGrayEventListener(CommunicableGrayTrackHolder grayTrackHolder, CommunicableGrayManager grayManager) {
        this.grayManager = grayManager;
        initHandlers();
        this.grayTrackHolder = grayTrackHolder;
    }

    @Override
    public void onEvent(GrayEventMsg msg) throws EventException {
        handleSource(msg);
    }

    private void handleSource(GrayEventMsg msg) {
        Optional.ofNullable(getHandler(msg.getSourceType())).orElse(
                msg1 -> handleUpdateInstance(msg1.getServiceId(), msg1.getInstanceId())
        ).accept(msg);
    }


    private Consumer<GrayEventMsg> getHandler(SourceType type) {
        return handers.get(type);
    }


    private void initHandlers() {
        putHandler(SourceType.GRAY_INSTANCE, this::handleGrayInstance)
                .putHandler(SourceType.GRAY_TRACK, this::handleGrayTrack);
    }

    private DefaultGrayEventListener putHandler(SourceType sourceType, Consumer<GrayEventMsg> handler) {
        handers.put(sourceType, handler);
        return this;
    }


    private void handleGrayInstance(GrayEventMsg msg) {
        InstanceLocalInfo instanceLocalInfo = getInstanceLocalInfo();
        if (instanceLocalInfo != null) {
            if (StringUtils.equals(msg.getServiceId(), instanceLocalInfo.getServiceId())
                    /*&& StringUtils.equals(msg.getInstanceId(), instanceLocalInfo.getInstanceId())*/) {
                return;
            }
        }
        switch (msg.getEventType()) {
            case DOWN:
                grayManager.closeGray(msg.getServiceId(), msg.getInstanceId());
            case UPDATE:
                handleUpdateInstance(msg.getServiceId(), msg.getInstanceId());
        }
    }

    private void handleUpdateInstance(String serviceId, String instanceId) {
        GrayInstance grayInstance = grayManager.getGrayInformationClient()
                .getGrayInstance(serviceId, instanceId);
        grayManager.updateGrayInstance(grayInstance);
    }

    private void handleGrayTrack(GrayEventMsg msg) {
        InstanceLocalInfo instanceLocalInfo = getInstanceLocalInfo();
        if (instanceLocalInfo == null) {
            log.warn("instanceLocalInfo is null");
            return;
        }
        if (!StringUtils.equals(msg.getServiceId(), instanceLocalInfo.getServiceId())) {
            return;
        }
        if (StringUtils.isNotEmpty(msg.getInstanceId())
                && !StringUtils.equals(msg.getInstanceId(), instanceLocalInfo.getInstanceId())) {
            return;
        }

        if (Objects.isNull(grayTrackHolder)) {
            return;
        }

        GrayTrackDefinition definition = (GrayTrackDefinition) msg.getSource();
        if (definition == null) {
            List<GrayTrackDefinition> definitions =
                    grayTrackHolder.getGrayInformationClient().getTrackDefinitions(msg.getServiceId(), msg.getInstanceId());
            if (definitions != null) {
                definitions.forEach(d -> {
                    grayTrackHolder.updateTrackDefinition(d);
                });
            }
        } else {
            switch (msg.getEventType()) {
                case DOWN:
                    grayTrackHolder.deleteTrackDefinition(definition.getName());
                case UPDATE:
                    grayTrackHolder.updateTrackDefinition(definition);
            }
        }


    }

    @Override
    public void setInstanceLocalInfo(InstanceLocalInfo instanceLocalInfo) {
        this.instanceLocalInfo = instanceLocalInfo;
    }

    public InstanceLocalInfo getInstanceLocalInfo() {
        if (instanceLocalInfo == null) {
            instanceLocalInfo = GrayClientHolder.getInstanceLocalInfo();
        }
        return instanceLocalInfo;
    }
}
