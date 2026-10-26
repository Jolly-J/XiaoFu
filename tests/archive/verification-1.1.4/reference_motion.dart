// Numerical harness of the supplied 咔捕 app_theme.dart formulas.
// Not bundled in the app; deliberately independent of the Kotlin implementation.
import 'dart:math' as math;
import 'dart:io';
double cubic(double x, double a, double b, double c, double d) {
  if (x == 0 || x == 1) return x;
  double f(double t, double v1, double v2) => 3*v1*(1-t)*(1-t)*t + 3*v2*(1-t)*t*t + t*t*t;
  var lo=0.0, hi=1.0;
  for(var i=0;i<45;i++) { final t=(lo+hi)/2; if(f(t,a,c)<x) {lo=t;} else {hi=t;} }
  return f((lo+hi)/2,b,d);
}
double displacement(double p) => math.log(1+4.5*p.clamp(0,1))/math.log(5.5);
void main() {
  final layers=StringBuffer('p,fgAlpha,fgX,bgAlpha,bgX,mask\n');
  for(final p in [0.0,.1,.25,.36,.4,.46,.7,.9,1.0]) {
    final stage=((p-.36)/.64).clamp(0.0,1.0);
    final bg=cubic(stage,.645,.045,.355,1);
    final fg=cubic((1-p/.46).clamp(0.0,1.0),.215,.61,.355,1);
    layers.writeln('$p,$fg,${.13*displacement(p)},$bg,${-.08*(1-displacement(stage))},${1-bg}');
  }
  File('app/src/test/resources/reference-back-layers.csv').writeAsStringSync(layers.toString());
  final trace=StringBuffer('frame,position,velocity\n');
  var p=0.0,v=0.0;
  final target=(math.log(1+2.4*.5)/math.log(3.4)).clamp(0.0,.96);
  for(var i=1;i<=60;i++) {
    const dt=1/60;
    v+=(115*(target-p)-19*v)*dt;
    p=(p+v*dt).clamp(0.0,.98);
    trace.writeln('$i,$p,$v');
  }
  File('app/src/test/resources/reference-back-spring.csv').writeAsStringSync(trace.toString());
}
