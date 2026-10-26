import android.media.*;import java.nio.*;
public class TrackProbe {
 public static void main(String[] args)throws Exception{
  MediaExtractor e=new MediaExtractor();e.setDataSource(args[0]);int chosen=-1;
  for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);System.out.println("TRACK "+i+" "+f);if(chosen<0&&f.getString("mime").startsWith("audio/"))chosen=i;}
  if(args.length>1)chosen=Integer.parseInt(args[1]);if(chosen<0){System.out.println("NO_AUDIO");return;}e.selectTrack(chosen);System.out.println("SELECTED="+chosen);
  ByteBuffer b=ByteBuffer.allocate(2*1024*1024);String[] tail=new String[4];int n=0,negative=0;long bytes=0;
  while(n<2000000){b.clear();long time=e.getSampleTime(),size=e.getSampleSize();int flags=e.getSampleFlags(),track=e.getSampleTrackIndex();int read=e.readSampleData(b,0);
   String line="sample="+n+" time="+time+" track="+track+" size="+size+" flags="+flags+" encryptedBit="+(flags&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)+" read="+read;
   if(read<0){for(int i=Math.max(3,n-3);i<n;i++)System.out.println("TAIL "+tail[i%4]);System.out.println("EOS "+line);break;}
   if(n<3)System.out.println("HEAD "+line);if(time<0)negative++;bytes+=read;tail[n%4]=line;n++;e.advance();
  }
  System.out.println("SUMMARY samples="+n+" bytes="+bytes+" negative_pts_samples="+negative);e.release();
 }
}
