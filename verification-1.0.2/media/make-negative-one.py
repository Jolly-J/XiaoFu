from pathlib import Path
import struct
p=Path(__file__).parent/'aac-editlist-default.mp4'; b=bytearray(p.read_bytes())
def boxes(start,end):
 while start+8<=end:
  size=struct.unpack_from('>I',b,start)[0]; kind=bytes(b[start+4:start+8]); header=8
  if size==1: size=struct.unpack_from('>Q',b,start+8)[0];header=16
  elif size==0: size=end-start
  assert size>=header and start+size<=end,(start,size,end)
  yield kind,start+header,start+size
  start+=size
def child(start,end,kind): return next((x for x in boxes(start,end) if x[0]==kind),None)
moov=child(0,len(b),b'moov')
for kind,s,e in boxes(moov[1],moov[2]):
 if kind!=b'trak':continue
 mdia=child(s,e,b'mdia'); hdlr=child(mdia[1],mdia[2],b'hdlr')
 if bytes(b[hdlr[1]+8:hdlr[1]+12])!=b'soun':continue
 mdhd=child(mdia[1],mdia[2],b'mdhd');pos=mdhd[1];assert b[pos]==0
 old=struct.unpack_from('>I',b,pos+12)[0]; dur=struct.unpack_from('>I',b,pos+16)[0]
 struct.pack_into('>II',b,pos+12,1000000,round(dur*1000000/old))
 minf=child(mdia[1],mdia[2],b'minf');stbl=child(minf[1],minf[2],b'stbl');stts=child(stbl[1],stbl[2],b'stts');n=struct.unpack_from('>I',b,stts[1]+4)[0]
 for i in range(n):
  delta_pos=stts[1]+8+i*8+4;delta=struct.unpack_from('>I',b,delta_pos)[0];struct.pack_into('>I',b,delta_pos,round(delta*1000000/old))
 edts=child(s,e,b'edts');elst=child(edts[1],edts[2],b'elst');assert b[elst[1]]==0;assert struct.unpack_from('>I',b,elst[1]+4)[0]==1
 struct.pack_into('>i',b,elst[1]+12,1)
 print('audio mdhd timebase',old,'->1000000; elst media_time=1 tick')
Path(__file__).with_name('aac-first-minus-one-us.mp4').write_bytes(b)
