import AppKit
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: CommandLine.arguments[1])
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let fm = FileManager.default
func color(_ hex: UInt32) -> CGColor {
    CGColor(colorSpace: cs, components: [CGFloat((hex >> 16) & 255)/255, CGFloat((hex >> 8) & 255)/255, CGFloat(hex & 255)/255, 1])!
}
func context(_ size: Int) -> CGContext {
    let c = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size*4, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.interpolationQuality = .high
    return c
}
func load(_ name: String) -> CGImage {
    let source = CGImageSourceCreateWithURL(root.appendingPathComponent(name) as CFURL, nil)!
    return CGImageSourceCreateImageAtIndex(source, 0, nil)!
}
func save(_ image: CGImage, _ name: String) {
    let url = root.appendingPathComponent(name)
    try! fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    precondition(CGImageDestinationFinalize(dest))
}
let fg = load("源图/foreground-generated.png")
let mono = load("源图/monochrome-generated.png")
func radius(_ image: CGImage) -> CGFloat {
    let n = image.width
    let c = context(n)
    c.draw(image, in: CGRect(x:0,y:0,width:n,height:n))
    let data = c.data!.assumingMemoryBound(to: UInt8.self)
    var r: CGFloat = 0
    for y in 0..<n {
        for x in 0..<n where data[(y*n+x)*4+3] > 0 {
            r = max(r,hypot(CGFloat(x)+0.5-CGFloat(n)/2,CGFloat(y)+0.5-CGFloat(n)/2))
        }
    }
    return r
}
let fgRadius = radius(fg)
let monoRadius = radius(mono)
let bgColor = color(0x332C30)
// Technical export only: preserve generated pixels, scale to a 64dp-diameter
// circle within the 108dp layer; reserve 1dp radial padding to the 66dp safe zone.
func layer(_ source: CGImage, _ r: CGFloat, _ n: Int) -> CGImage {
    let c = context(n)
    let side = CGFloat(source.width) * CGFloat(n) * 32 / 108 / r
    c.draw(source,in:CGRect(x:(CGFloat(n)-side)/2,y:(CGFloat(n)-side)/2,width:side,height:side))
    return c.makeImage()!
}
func background(_ n: Int) -> CGImage {
    let c = context(n); c.setFillColor(bgColor)
    c.fill(CGRect(x:0,y:0,width:n,height:n)); return c.makeImage()!
}
func icon(_ n: Int, shape: String, themed: Bool = false) -> CGImage {
    let c = context(n)
    let rect = CGRect(x:0,y:0,width:n,height:n)
    if shape == "circle" { c.addEllipse(in:rect); c.clip() }
    else if shape == "rounded" {
        c.addPath(CGPath(roundedRect:rect,cornerWidth:CGFloat(n)*0.23,cornerHeight:CGFloat(n)*0.23,transform:nil)); c.clip()
    } else if shape == "squircle" {
        let path = CGMutablePath()
        for i in 0...360 {
            let t = CGFloat(i) * .pi / 180
            let ct = cos(t), st = sin(t)
            let x = CGFloat(n)/2 * (1 + (ct < 0 ? -1:1) * pow(abs(ct),0.5))
            let y = CGFloat(n)/2 * (1 + (st < 0 ? -1:1) * pow(abs(st),0.5))
            if i == 0 { path.move(to:CGPoint(x:x,y:y)) } else { path.addLine(to:CGPoint(x:x,y:y)) }
        }
        path.closeSubpath(); c.addPath(path); c.clip()
    }
    c.setFillColor(themed ? color(0xE4D7C4) : bgColor); c.fill(rect)
    // Launcher viewport is the central 72dp of the 108dp layer.
    let expanded = CGRect(x:-CGFloat(n)*0.25,y:-CGFloat(n)*0.25,width:CGFloat(n)*1.5,height:CGFloat(n)*1.5)
    if themed {
        let mask = layer(mono,monoRadius,1080)
        c.saveGState(); c.clip(to:expanded,mask:mask)
        c.setFillColor(color(0x594936)); c.fill(expanded); c.restoreGState()
    } else { c.draw(layer(fg,fgRadius,1080),in:expanded) }
    return c.makeImage()!
}
save(layer(fg,fgRadius,1080),"通用分层素材/foreground-1080.png")
save(layer(mono,monoRadius,1080),"通用分层素材/monochrome-1080.png")
save(background(1080),"通用分层素材/background-1080.png")
for (density,adaptive,legacy) in [("mdpi",108,48),("hdpi",162,72),("xhdpi",216,96),("xxhdpi",324,144),("xxxhdpi",432,192)] {
    save(layer(fg,fgRadius,adaptive),"android/res/drawable-\(density)/xiaofu_launcher_foreground.png")
    save(layer(mono,monoRadius,adaptive),"android/res/drawable-\(density)/xiaofu_launcher_monochrome.png")
    save(icon(legacy,shape:"rounded"),"android/res/mipmap-\(density)/ic_launcher.png")
    save(icon(legacy,shape:"circle"),"android/res/mipmap-\(density)/ic_launcher_round.png")
}
save(icon(512,shape:"square"),"商店图标/google-play-512.png")
for shape in ["circle","rounded","squircle"] {
    save(icon(360,shape:shape),"预览/\(shape).png")
}
save(icon(360,shape:"circle",themed:true),"预览/themed.png")
// Contact sheet is a technical mask simulation, not a device screenshot.
let sheet = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:1200,pixelsHigh:430,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep:sheet)
NSColor(calibratedWhite:0.95,alpha:1).setFill()
NSBezierPath(rect:NSRect(x:0,y:0,width:1200,height:430)).fill()
for (i,item) in [("circle","圆形裁切"),("rounded","圆角方形裁切"),("squircle","超椭圆裁切"),("themed","单色主题预览")].enumerated() {
    let image = NSImage(contentsOf:root.appendingPathComponent("预览/\(item.0).png"))!
    image.draw(in:NSRect(x:i*300+24,y:94,width:252,height:252))
    let style = NSMutableParagraphStyle(); style.alignment = .center
    (item.1 as NSString).draw(in:NSRect(x:i*300,y:45,width:300,height:30),withAttributes:[.font:NSFont.systemFont(ofSize:19),.foregroundColor:NSColor.darkGray,.paragraphStyle:style])
}
NSGraphicsContext.restoreGraphicsState()
try! sheet.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent("预览/不同形状预览.png"))
print("Exported layers, legacy icons, Play icon and mask previews. Radii: \(fgRadius), \(monoRadius)")
