import android.media.*;
import android.os.Build;
import java.nio.*;
import java.io.File;
import java.util.*;

/** Standalone app_process probe, synthetic PCM only; no network, user files, or keys. */
public final class CodecCapabilitiesProbe {
  static final String MIME = "audio/mp4a-latm";
  static MediaFormat format(int profile) {
    MediaFormat f = MediaFormat.createAudioFormat(MIME, 44100, 2);
    f.setInteger(MediaFormat.KEY_AAC_PROFILE, profile);
    f.setInteger(MediaFormat.KEY_BIT_RATE, profile == 29 ? 48000 : 64000);
    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
    return f;
  }
  public static void main(String[] args) throws Exception {
    System.out.println("AAC_PROBE sdk=" + Build.VERSION.SDK_INT);
    Set<Integer> generated = new HashSet<>();
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
      for (String mime : info.getSupportedTypes()) {
        if (!MIME.equalsIgnoreCase(mime)) continue;
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
        StringBuilder profiles = new StringBuilder();
        for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) profiles.append(level.profile).append(':').append(level.level).append(',');
        System.out.println("CODEC name=" + info.getName() + " encoder=" + info.isEncoder() + " profiles=" + profiles);
        for (int p : new int[]{5,29}) {
          boolean listed=false;
          for (MediaCodecInfo.CodecProfileLevel l:caps.profileLevels) if(l.profile==p) listed=true;
          boolean supported;
          try { supported = caps.isFormatSupported(format(p)); }
          catch (RuntimeException e) { supported=false; }
          System.out.println("PROFILE name="+info.getName()+" profile="+p+" listed="+listed+" formatSupported="+supported);
          if (args.length > 0 && info.isEncoder() && listed && supported && !generated.contains(p)) {
            File out=new File(args[0],"platform-he-profile-"+p+".m4a");
            try { encode(info.getName(),p,out); generated.add(p); }
            catch (Exception e) { out.delete(); System.out.println("GENERATE_FAIL profile="+p+" exception="+e.getClass().getSimpleName()); }
          }
        }
      }
    }
    for(int p:new int[]{5,29}) System.out.println("PROFILE_RESULT profile="+p+" generated="+generated.contains(p));
  }
  static void encode(String name,int profile,File out) throws Exception {
    MediaCodec codec=MediaCodec.createByCodecName(name);
    MediaMuxer muxer=null;
    boolean started=false, muxStarted=false;
    try {
      codec.configure(format(profile),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start(); started=true;
      muxer=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
      final int total=44100*8;
      int submitted=0,track=-1,packets=0; boolean inputEos=false,done=false;
      long deadline=System.nanoTime()+120_000_000_000L;
      while(!done) {
        if(System.nanoTime()>deadline) throw new IllegalStateException("encode_timeout");
        if(!inputEos) {
          int index=codec.dequeueInputBuffer(10000);
          if(index>=0) {
            ByteBuffer input=codec.getInputBuffer(index); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN);
            int frames=Math.min(input.remaining()/4,total-submitted);
            for(int f=0;f<frames;f++) for(int ch=0;ch<2;ch++)
              input.putShort((short)(9000*Math.sin(2*Math.PI*(ch==0?440:660)*(submitted+f)/44100.0)));
            long pts=submitted*1000000L/44100;
            submitted+=frames;
            int flags=submitted==total?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0;
            codec.queueInputBuffer(index,0,frames*4,pts,flags);
            inputEos=flags!=0;
          }
        }
        int index=codec.dequeueOutputBuffer(bi,10000);
        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat output=codec.getOutputFormat();
          System.out.println("ENCODE_FORMAT profile="+profile+" format="+output);
          track=muxer.addTrack(output); muxer.start(); muxStarted=true;
        } else if(index>=0) {
          ByteBuffer output=codec.getOutputBuffer(index);
          if(bi.size>0 && (bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
            if(!muxStarted) throw new IllegalStateException("no_output_format");
            output.position(bi.offset);output.limit(bi.offset+bi.size);
            muxer.writeSampleData(track,output,bi);packets++;
          }
          done=(bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
          codec.releaseOutputBuffer(index,false);
        }
      }
      if(packets==0) throw new IllegalStateException("empty_output");
      muxer.stop(); muxStarted=false;
      System.out.println("GENERATED profile="+profile+" packets="+packets+" bytes="+out.length());
    } finally {
      if(started) try{codec.stop();}catch(Exception ignored){} codec.release();
      if(muxer!=null){if(muxStarted)try{muxer.stop();}catch(Exception ignored){} muxer.release();}
    }
  }
}
