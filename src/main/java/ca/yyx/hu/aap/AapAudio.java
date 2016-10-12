package ca.yyx.hu.aap;

import ca.yyx.hu.utils.Utils;

/**
 * @author algavris
 * @date 01/10/2016.
 */

class AapAudio {
    private final AapTransport mTransport;

    private static final int AUDIO_BUFS_SIZE = 65536 * 4;      // Up to 256 Kbytes
    private static final int AUDIO_BUFS_NUM = 16;

    private int num_aud_buf_bufs = AUDIO_BUFS_NUM;

    private byte[][] aud_buf_bufs = new byte[AUDIO_BUFS_NUM][AUDIO_BUFS_SIZE];

    private int[] aud_buf_lens = new int[AUDIO_BUFS_NUM];

    private int aud_buf_buf_tail = 0;    // Tail is next index for writer to write to.   If head = tail, there is no info.
    private int aud_buf_buf_head = 0;    // Head is next index for reader to read from.

    private int aud_buf_errs = 0;
    private int aud_max_bufs = 0;
    private int aud_sem_tail = 0;
    private int aud_sem_head = 0;

    private byte ack_val_aud = 0;
    private byte ack_val_au1 = 0;
    private byte ack_val_au2 = 0;

    private int out_state_aud = -1;
    private int out_state_au1 = -1;
    private int out_state_au2 = -1;

    private byte aud_ack[] = {(byte) 0x80, 0x04, 0x08, 0, 0x10, 1};

    AapAudio(AapTransport transport) {
        mTransport = transport;
    }
    // Global Ack: 0, 1     Same as video ack ?

    int state(int chan) {
        int state = 0;
        if (chan == Channel.AA_CH_AUD) {
            state = out_state_aud;                                            // Get current audio output state change
            out_state_aud = -1;                                               // Reset audio output state change indication
        }
        else if (chan == Channel.AA_CH_AU1) {
            state = out_state_au1;                                            // Get current audio output state change
            out_state_au1 = -1;                                               // Reset audio output state change indication
        }
        else if (chan == Channel.AA_CH_AU2) {
            state = out_state_au2;                                            // Get current audio output state change
            out_state_au2 = -1;                                               // Reset audio output state change indication
        }
        return (state);                                                     // Return what the new state was before reset
    }

    //int aud_ack_ctr = 0;
    int process(int chan, int msg_type, int flags, byte[] buf, int len) {
        // 300 ms @ 48000/sec   samples = 14400     stereo 16 bit results in bytes = 57600

        //logd ("iaap_audio_process chan: %d  msg_type: %d  flags: 0x%x  buf: %p  len: %d", chan, msg_type, flags, buf, len); // iaap_audio_process msg_type: 0  flags: 0xb  buf: 0xe08cbfb8  len: 8202

        if (chan == Channel.AA_CH_AU1)
            aud_ack[3] = ack_val_au1;
        else if (chan == Channel.AA_CH_AU2)
            aud_ack[3] = ack_val_au2;
        else
            aud_ack[3] = ack_val_aud;

        int ret = mTransport.sendEncrypted(chan, aud_ack, aud_ack.length);      // Respond with ACK (for all fragments ?)

        //hex_dump ("AUDIO: ", 16, buf, len);
        if (len >= 10) {
            int ctr = 0;
            long ts = 0, t2 = 0;
            for (ctr = 2; ctr <= 9; ctr++) {
                ts = ts << 8;
                t2 = t2 << 8;
                ts += (long) buf[ctr];
                t2 += buf[ctr];
                if (ctr == 6)
                    Utils.logv ("iaap_audio_process ts: %d 0x%x  t2: %d 0x%x", ts, ts, t2, t2);
            }
            Utils.logv ("iaap_audio_process ts: %d 0x%x  t2: %d 0x%x", ts, ts, t2, t2);
/*
07-02 03:33:26.486 W/                        hex_dump( 1549): AUDIO:  00000000 00 00 00 00 00 79 3e 5c bd 60 45 ef 6c 1a 79 f6
07-02 03:33:26.486 W/                        hex_dump( 1549): AUDIO:      0010 a8 15 15 fe b3 14 8c fc e8 0c 34 f8 bf 02 ec 00
07-02 03:33:26.486 W/                        hex_dump( 1549): AUDIO:      0020 ab 0a 9a 0d a1 1d 88 0a ae 1e e5 03 a9 16 8d 10
07-02 03:33:26.486 W/                        hex_dump( 1549): AUDIO:      0030 d9 1f 3c 28 af 34 9b 35 e2 3e e2 36 fd 3c b4 34
07-02 03:33:26.487 D/              iaap_audio_process( 1549): iaap_audio_process ts: 31038 0x793e  t2: 31038 0x793e
07-02 03:33:26.487 D/              iaap_audio_process( 1549): iaap_audio_process ts: 1046265184 0x3e5cbd60  t2: 1046265184 0x3e5cbd60
*/
            decode(chan, 10, buf, len - 10);//assy, assy_size);                                                                                    // Decode PCM audio fully re-assembled
        }

        return (0);
    }


    private void decode(int chan,int start, byte[] buf, int len) {


        if (len > AUDIO_BUFS_SIZE) {
            Utils.loge ("Error audio len: %d  aud_buf_BUFS_SIZE: %d", len, AUDIO_BUFS_SIZE);
            len = AUDIO_BUFS_SIZE;
        }

        byte[] q_buf = aud_write_tail_buf_get(len);
        // Get queue buffer tail to write to     !!! Need to lock until buffer written to !!!!
//      logd ("audio q_buf: %p  buf: %p  len: %d", q_buf, buf, len);
        if (q_buf == null) {
            Utils.loge ("Error audio no q_buf: %p  buf: %p  len: %d", q_buf, buf, len);
            //return;                                                         // Continue in order to write to record file
        } else {
            System.arraycopy(buf, start, q_buf, 0, len);
        }
    }

    int buffersCount() {
        return aud_buf_buf_tail - aud_buf_buf_head;
    }

    ByteArray aud_read_head_buf_get() {                              // Get head buffer to read from

        int bufs = aud_buf_buf_tail - aud_buf_buf_head;
        if (bufs < 0)                                                      // If underflowed...
            bufs += num_aud_buf_bufs;                                          // Wrap
        //logd ("aud_read_head_buf_get start bufs: %d  head: %d  tail: %d", bufs, aud_buf_buf_head, aud_buf_buf_tail);

        if (bufs <= 0) {                                                    // If no buffers are ready...
            //logd ("aud_read_head_buf_get no aud_buf_bufs");
            //aud_buf_errs ++;  // Not an error; just no data
            //aud_buf_buf_tail = aud_buf_buf_head = 0;                          // Drop all buffers
            return null;
        }

        int max_retries = 4;
        int retries = 0;
        for (retries = 0; retries < max_retries; retries++) {
            aud_sem_head++;
            if (aud_sem_head == 1)
                break;
            aud_sem_head--;
            Utils.loge ("aud_sem_head wait");
            Utils.ms_sleep(10);
        }
        if (retries >= max_retries) {
            Utils.loge ("aud_sem_head could not be acquired");
            return null;
        }

        if (aud_buf_buf_head < 0 || aud_buf_buf_head > num_aud_buf_bufs - 1)   // Protect
            aud_buf_buf_head &= num_aud_buf_bufs - 1;

        aud_buf_buf_head++;

        if (aud_buf_buf_head < 0 || aud_buf_buf_head > num_aud_buf_bufs - 1)
            aud_buf_buf_head &= num_aud_buf_bufs - 1;

        ByteArray result = new ByteArray(AUDIO_BUFS_SIZE);
        result.data = aud_buf_bufs[aud_buf_buf_head];
        result.length = aud_buf_lens[aud_buf_buf_head];

        aud_sem_head--;

        return result;
    }

    private byte[] aud_write_tail_buf_get(int len) {                          // Get tail buffer to write to

        if (len > AUDIO_BUFS_SIZE) {
            Utils.loge ("!!!!!!!!!! aud_write_tail_buf_get too big len: %d", len);   // E/aud_write_tail_buf_get(10699): !!!!!!!!!! aud_write_tail_buf_get too big len: 66338
            return null;
        }

        int bufs = aud_buf_buf_tail - aud_buf_buf_head;
        if (bufs < 0) {                                                     // If underflowed...
            bufs += num_aud_buf_bufs;                                         // Wrap
        }
        //logd ("aud_write_tail_buf_get start bufs: %d  head: %d  tail: %d", bufs, aud_buf_buf_head, aud_buf_buf_tail);

        if (bufs > aud_max_bufs)                                            // If new maximum buffers in progress...
            aud_max_bufs = bufs;                                              // Save new max
        if (bufs >= num_aud_buf_bufs - 1) {                                 // If room for another (max = NUM_aud_buf_BUFS - 1)
            Utils.loge ("aud_write_tail_buf_get out of aud_buf_bufs");
            aud_buf_errs++;
            //aud_buf_buf_tail = aud_buf_buf_head = 0;                        // Drop all buffers
            return null;
        }

        int max_retries = 4;
        int retries = 0;
        for (retries = 0; retries < max_retries; retries++) {
            aud_sem_tail++;
            if (aud_sem_tail == 1)
                break;
            aud_sem_tail--;
            Utils.loge ("aud_sem_tail wait");
            Utils.ms_sleep(10);
        }
        if (retries >= max_retries) {
            Utils.loge ("aud_sem_tail could not be acquired");
            return null;
        }

        if (aud_buf_buf_tail < 0 || aud_buf_buf_tail > num_aud_buf_bufs - 1)   // Protect
            aud_buf_buf_tail &= num_aud_buf_bufs - 1;

        aud_buf_buf_tail++;

        if (aud_buf_buf_tail < 0 || aud_buf_buf_tail > num_aud_buf_bufs - 1)
            aud_buf_buf_tail &= num_aud_buf_bufs - 1;

        byte[] ret = aud_buf_bufs[aud_buf_buf_tail];
        aud_buf_lens[aud_buf_buf_tail] = len;

        //logd ("aud_write_tail_buf_get done  ret: %p  bufs: %d  tail len: %d  head: %d  tail: %d", ret, bufs, len, aud_buf_buf_head, aud_buf_buf_tail);

        aud_sem_tail--;

        return ret;
    }

    void setAudioAckVal(int chan, byte value) {
        if (chan == Channel.AA_CH_AUD)
            ack_val_aud = value;                                            // Save value for audio acks
        else if (chan == Channel.AA_CH_AU1)
            ack_val_au1 = value;                                            // Save value for audio1 acks
        else if (chan == Channel.AA_CH_AU2)
            ack_val_au2 = value;                                            // Save value for audio2 acks
    }

    void setOutState(int chan, int state) {
        if (chan == Channel.AA_CH_AUD)
            out_state_aud = state;                                                      // Signal Audio stop
        else if (chan == Channel.AA_CH_AU1)
            out_state_au1 = state;                                                      // Signal Audio1 stop
        else if (chan == Channel.AA_CH_AU2)
            out_state_au2 = state;                                                      // Signal Audio2 stop
    }
}