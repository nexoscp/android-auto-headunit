package ca.yyx.hu.aap;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;
import com.google.protobuf.nano.MessageNano;

import ca.yyx.hu.aap.protocol.Channel;
import ca.yyx.hu.aap.protocol.MsgType;
import ca.yyx.hu.aap.protocol.nano.Protocol;
import ca.yyx.hu.decoder.MicRecorder;
import ca.yyx.hu.utils.AppLog;
import ca.yyx.hu.utils.NightMode;
import ca.yyx.hu.utils.Utils;

/**
 * @author algavris
 * @date 01/10/2016.
 */

class AapControl {
    private final MicRecorder mMicRecorder;
    private final AapAudio mAapAudio;
    private final String mBtMacAddress;
    private final AapTransport mTransport;

    AapControl(AapTransport transport, MicRecorder recorder, AapAudio audio, String btMacAddress) {
        mTransport = transport;
        mMicRecorder = recorder;
        mAapAudio = audio;
        mBtMacAddress = btMacAddress;
    }

    int execute(AapIncomingMessage message) throws InvalidProtocolBufferNanoException {

        if (message.type == 7)
        {
            Protocol.ChannelOpenRequest request = parse(new Protocol.ChannelOpenRequest(), message);
            return channel_open_request(request, message.channel, message.data);
        }

        switch (message.channel)
        {
            case Channel.AA_CH_CTR:
                return executeControl(message);
            case Channel.AA_CH_TOU:
                return executeTouch(message);
            case Channel.AA_CH_SEN:
                return executeSensor(message);
            case Channel.AA_CH_VID:
            case Channel.AA_CH_AUD:
            case Channel.AA_CH_AU1:
            case Channel.AA_CH_AU2:
            case Channel.AA_CH_MIC:
                return executeMedia(message);
        }
        return 0;
    }

    private int executeMedia(AapIncomingMessage message) throws InvalidProtocolBufferNanoException {

        switch (message.type)
        {
            case MsgType.Media.SETUPREQUEST:
                Protocol.MediaSetupRequest setupRequest = parse(new Protocol.MediaSetupRequest(), message);
                return media_sink_setup_request(setupRequest, message.channel);
            case MsgType.Media.STARTREQUEST:
                Protocol.Start startRequest = parse(new Protocol.Start(), message);
                return media_start_request(startRequest, message.channel);
            case MsgType.Media.STOPREQUEST:
                return media_sink_stop_request(message.channel);
            case MsgType.Media.VIDEOFOCUSREQUESTNOTIFICATION:
                Protocol.VideoFocusRequestNotification focusRequest = parse(new Protocol.VideoFocusRequestNotification(), message);
                AppLog.i("Video Focus Request - disp_id: %d, mode: %d, reason: %d", focusRequest.dispChannelId, focusRequest.mode, focusRequest.reason);
                return 0;
            case MsgType.Media.MICREQUEST:
                Protocol.MicrophoneRequest micRequest = parse(new Protocol.MicrophoneRequest(), message);
                return mic_request(micRequest);
            default:
                AppLog.e("Unsupported");
        }
        return 0;
    }

    private int mic_request(Protocol.MicrophoneRequest micRequest) {
        AppLog.d("Mic request: %s", micRequest);

        if (micRequest.open) {
            mMicRecorder.start();
        } else {
            mMicRecorder.stop();
        }
        return 0;
    }

    private int media_sink_stop_request(int channel) {
        AppLog.i("Media Sink Stop Request");
        if (Channel.isAudio(channel)) {
            mAapAudio.stopAudio(channel);
        }
        return 0;
    }

    private int executeTouch(AapIncomingMessage message) throws InvalidProtocolBufferNanoException {

        switch (message.type)
        {
            case MsgType.Input.BINDINGREQUEST:
                Protocol.KeyBindingRequest request = parse(new Protocol.KeyBindingRequest(), message);
                return input_binding(request, message.channel);
            default:
                AppLog.e("Unsupported");
        }
        return 0;
    }


    private int executeSensor(AapIncomingMessage message) throws InvalidProtocolBufferNanoException {
        // 0 - 31, 32768-32799, 65504-65535
        switch (message.type)
        {
            case MsgType.Sensor.STARTREQUEST:
                Protocol.SensorRequest request = parse(new Protocol.SensorRequest(), message);
                return sensor_start_request(request, message.channel);
            default:
                AppLog.e("Unsupported");
        }
        return 0;
    }

    private int executeControl(AapIncomingMessage message) throws InvalidProtocolBufferNanoException {

        switch (message.type)
        {
            case MsgType.Control.SERVICEDISCOVERYREQUEST:
                Protocol.ServiceDiscoveryRequest request = parse(new Protocol.ServiceDiscoveryRequest(), message);
                return service_discovery_request(request, message.channel);
            case MsgType.Control.PINGREQUEST:
                Protocol.PingRequest pingRequest = parse(new Protocol.PingRequest(), message);
                return ping_request(pingRequest, message.channel, message.data);
            case MsgType.Control.NAVFOCUSREQUESTNOTIFICATION:
                Protocol.NavFocusRequestNotification navigationFocusRequest = parse(new Protocol.NavFocusRequestNotification(), message);
                return navigation_focus_request(navigationFocusRequest, message.channel, message.data);
            case MsgType.Control.BYEYEREQUEST:
                Protocol.ByeByeRequest shutdownRequest = parse(new Protocol.ByeByeRequest(), message);
                return byebye_request(shutdownRequest, message.channel);
            case MsgType.Control.SHUTDOWNRESPONSE:
                AppLog.i("Byebye Response");
                return -1;
            case MsgType.Control.VOICESESSIONNOTIFICATION:
                Protocol.VoiceSessionNotification voiceRequest = parse(new Protocol.VoiceSessionNotification(), message);
                return voice_session_notification(voiceRequest);
            case MsgType.Control.AUDIOFOCUSREQUESTNOTFICATION:
                Protocol.AudioFocusRequestNotification audioFocusRequest = parse(new Protocol.AudioFocusRequestNotification(), message);
                return audio_focus_request(audioFocusRequest, message.channel, message.data);
            default:
                AppLog.e("Unsupported");
        }
        return 0;
    }

    private static <T extends MessageNano> T parse(T msg, final AapIncomingMessage message) throws InvalidProtocolBufferNanoException
    {
        return  MessageNano.mergeFrom(msg, message.data, message.dataOffset, message.length - message.dataOffset);
    }

    private int media_start_request(Protocol.Start request, int channel) {
        AppLog.i("Media Start Request %s: %s", Channel.name(channel), request);

        mTransport.setSessionId(channel, request.sessionId);
        return 0;
    }

    private int media_sink_setup_request(Protocol.MediaSetupRequest request, int channel) {

        AppLog.i("Media Sink Setup Request: %d", request.type);
        // R 2 VID b 00000000 08 03
        // R 4 AUD b 00000000 08 01

        Protocol.Config configResponse = new Protocol.Config();
        configResponse.status = Protocol.Config.CONFIG_STATUS_2;
        configResponse.maxUnacked = 1;
        configResponse.configurationIndices = new int[] { 0 };

        byte[] ba = Messages.createByteArray(MsgType.Media.CONFIGRESPONSE, configResponse);

        int ret = mTransport.sendEncrypted(channel, ba, ba.length);

        if (channel == Channel.AA_CH_VID) {
            mTransport.gainVideoFocus();
        }

        return ret;
    }

    private int input_binding(Protocol.KeyBindingRequest request, int channel) {
        AppLog.i("Input binding request %s", request);

        byte[] ba = Messages.createByteArray(MsgType.Input.BINDINGRESPONSE, new Protocol.BindingResponse());
        return mTransport.sendEncrypted(channel, ba, ba.length);
    }

    private int sensor_start_request(Protocol.SensorRequest request, int channel) {
         AppLog.i("Sensor Start Request sensor: %d, minUpdatePeriod: %d", request.type, request.minUpdatePeriod);

        // R 1 SEN b 00000000 08 01 10 00     Sen: 1, 10, 3, 8, 7
        // Yes: SENSOR_TYPE_COMPASS/LOCATION/RPM/DIAGNOSTICS/GEAR      No: SENSOR_TYPE_DRIVING_STATUS
        byte[] ba = Messages.createByteArray(MsgType.Sensor.STARTRESPONSE, new Protocol.SensorResponse());
        mTransport.sendEncrypted(channel, ba, ba.length);

        if (request.type == 10) {
            // If Sensor channel...
            Utils.ms_sleep(2);
            byte[] ba1 = Messages.createDrivingStatusEvent(0);
            AppLog.i("Send driving status");
            mTransport.sendEncrypted(Channel.AA_CH_SEN, ba1, ba1.length);
            Utils.ms_sleep(2);
            NightMode nm = new NightMode();
            AppLog.i("Send night mode");
            mTransport.sendNightMode(nm.current());
            AppLog.i(nm.toString());
        }

        return 0;
    }

    private int channel_open_request(Protocol.ChannelOpenRequest request, int channel, byte[] buf) {
        // Channel Open Request
        AppLog.i("Channel Open Request - priority: %d  chan: %d %s", request.priority, request.serviceId, Channel.name(request.serviceId));

        Protocol.ChannelOpenResponse response = new Protocol.ChannelOpenResponse();
        response.status = Protocol.STATUS_OK;

        Messages.serializeByteArray(MsgType.Control.CHANNELOPENRESPONSE, response, buf);
        int ret = mTransport.sendEncrypted(channel, buf, response.getSerializedSize() + 2);
        AppLog.i("Channel Open Response: %d", ret);
        return 0;
    }

    private int service_discovery_request(Protocol.ServiceDiscoveryRequest request, int channel) throws InvalidProtocolBufferNanoException {                  // Service Discovery Request
        AppLog.i("Service Discovery Request: %s", request.phoneName);                               // S 0 CTR b src: HU  lft:   113  msg_type:     6 Service Discovery Response    S 0 CTR b 00000000 0a 08 08 01 12 04 0a 02 08 0b 0a 13 08 02 1a 0f

        byte[] serviceDiscoveryResponse = Messages.createServiceDiscoveryResponse(mBtMacAddress);
        return mTransport.sendEncrypted(channel, serviceDiscoveryResponse, serviceDiscoveryResponse.length);                // Send Service Discovery Response from sd_buf
    }

    private int ping_request(Protocol.PingRequest request, int channel, byte[] buf) {
        AppLog.i("Ping Request: %d", request.timestamp);

        // Channel Open Response
        Protocol.PingResponse response = new Protocol.PingResponse();
        response.timestamp = System.nanoTime();
        Messages.serializeByteArray(MsgType.Control.PINGRESPONSE, response, buf);
        return mTransport.sendEncrypted(channel, buf, response.getSerializedSize() + 2);
    }

    private int navigation_focus_request(Protocol.NavFocusRequestNotification request, int channel, byte[] buf) {
        AppLog.i("Navigation Focus Request: %d", request.focusType);

        // Send Navigation Focus Notification
        Protocol.NavFocusNotification response = new Protocol.NavFocusNotification();
        response.focusType = Protocol.NAV_FOCUS_2;
        Messages.serializeByteArray(MsgType.Control.NAVFOCUSRNOTIFICATION, response, buf);

        mTransport.sendEncrypted(channel, buf, 4);
        return 0;
    }

    private int byebye_request(Protocol.ByeByeRequest request, int channel) {
        if (request.reason == 1)
            AppLog.i("Byebye Request reason: 1 AA Exit Car Mode");
        else
            AppLog.e("Byebye Request reason: %d", request.reason);

        mTransport.sendEncrypted(channel, Messages.BYEBYE_RESPONSE, Messages.BYEBYE_RESPONSE.length);
        Utils.ms_sleep(100);
        return -1;
    }

    private int voice_session_notification(Protocol.VoiceSessionNotification request) {                  // sr:  00000000 00 11 08 01      Microphone voice search usage     sr:  00000000 00 11 08 02
        if (request.status == Protocol.VoiceSessionNotification.VOICE_STATUS_START)
            AppLog.i("Voice Session Notification: 1 START");
        else if (request.status== Protocol.VoiceSessionNotification.VOICE_STATUS_STOP)
            AppLog.i("Voice Session Notification: 2 STOP");
        else
            AppLog.e("Voice Session Notification: %d", request.status);
        return (0);
    }

    private int audio_focus_request(Protocol.AudioFocusRequestNotification notification, int channel, byte[] buf) throws InvalidProtocolBufferNanoException {                  // Audio Focus Request
        if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_GAIN)
            AppLog.i("Audio Focus Request: 1 AUDIO_FOCUS_GAIN");
        else if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_GAIN_TRANSIENT)
            AppLog.i("Audio Focus Request: 2 AUDIO_FOCUS_GAIN_TRANSIENT");
        else if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_UNKNOWN)
            AppLog.i("Audio Focus Request: 3 gain/release ?");
        else if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_RELEASE)
            AppLog.i("Audio Focus Request: 4 AUDIO_FOCUS_RELEASE");
        else
            AppLog.e("Audio Focus Request: %d", notification.request);

        mAapAudio.requestFocusChange(notification.request);

        Protocol.AudioFocusNotification response = new Protocol.AudioFocusNotification();
        if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_RELEASE) {
            response.focusState = Protocol.AudioFocusNotification.AUDIO_FOCUS_STATE_LOSS;
        } else if (notification.request == Protocol.AudioFocusRequestNotification.AUDIO_FOCUS_GAIN_TRANSIENT) {
            response.focusState = Protocol.AudioFocusNotification.AUDIO_FOCUS_STATE_GAIN;
        } else {
            response.focusState = Protocol.AudioFocusNotification.AUDIO_FOCUS_STATE_GAIN;
        }
        Messages.serializeByteArray(MsgType.Control.AUDIOFOCUSNOTFICATION, response, buf);
        mTransport.sendEncrypted(channel, buf, 4);
        return 0;
    }

}