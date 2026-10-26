import android.media.*;
import android.os.Build;
import java.nio.ByteBuffer;

/** Synthetic-fixture-only input path supplied by test runner; prints numerical media metadata. */
public final class DecodeProbe {
 public static void main(String[] args) throws Exception {
  MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;
  try {
   ex.setDataSource(args[0]);int track=-1;
   for(int i=0;i<ex.getTrackCount();i++) if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}
   if(track<0)throw new IllegalStateException("no_audio");
   MediaFormat input=ex.getTrackFormat(track);ex.selectTrack(track);
   System.out.println("INPUT sdk="+Build.VERSION.SDK_INT+" format="+input);
   codec=MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));codec.configure(input,null,null,0);codec.start();
   MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   boolean inEos=false,outEos=false;long bytes=0;int samples=0,rate=0,channels=0;
   long deadline=System.nanoTime()+120_000_000_000L;
   while(!outEos) {
    if(System.nanoTime()>deadline)throw new IllegalStateException("timeout");
    if(!inEos) {
     int index=codec.dequeueInputBuffer(10000);
     if(index>=0) {
      ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();
      int size=ex.getSampleTrackIndex()<0?-1:ex.readSampleData(buffer,0);
      if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inEos=true;}
      else {codec.queueInputBuffer(index,0,size,ex.getSampleTime(),0);ex.advance();samples++;}
     }
    }
    int index=codec.dequeueOutputBuffer(info,10000);
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
     MediaFormat output=codec.getOutputFormat();System.out.println("OUTPUT format="+output);
     rate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    }else if(index>=0) {
     bytes+=info.size;outEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);
    }
   }
   System.out.println("DECODE_COMPLETE packets="+samples+" bytes="+bytes+" rate="+rate+" channels="+channels+" seconds="+(bytes/(rate*channels*2.0)));
  }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}ex.release();}
 }
}
