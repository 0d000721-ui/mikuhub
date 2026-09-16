# Miku launcher icon

Generated using the built-in image generation tool; its interface does not expose a selectable model version. The earlier glossy draft was replaced after user feedback.

- Master: `miku-launcher-master.png`
- Runtime: `app/src/main/res/drawable-nodpi/ic_launcher_miku_art.png` (432 × 432)
- Adaptive layer: `ic_launcher_miku_foreground.xml`, with an 18 dp inset in the standard 108 dp canvas.
- Background: white. The existing rabbit monochrome layer is retained for Android themed icons.
- User reference: six supplied home-screen screenshots; icon design and small chibi stickers, not wallpaper.
- Additional style research: https://vocasphere.net/2021/02/project-sekai-stamps-translation-compilation/

## Final generation prompt

任务：修改图1中的 RikkaHub 安卓图标，下方初音角色必须明显重画，消除精致AI头像感。图1是编辑目标；图2至图5是用户桌面截图的代表，仅参考其中 APP 图标底部的 Q 版初音小贴纸，完全忽略大面积壁纸。已经综合了用户六张截图的视觉特点，最重要的参考是作业帮、Gmail、黑阈、微信和 Scene 图标里的那种简单手绘小人。
保留：图1上方的深青黑色 RikkaHub 兔头符号，原有长耳朵、两只竖椭圆眼睛和整体标志形状、位置。只重画下方初音，并把背景改成纯白。
下方初音的新设计：真正的简笔Q版表情贴纸，圆圆的略扁的包子脸，小小的身体趴在图标下方，双手轻轻扒着底边。两条双马尾是两块简单下垂的哑光青绿色大色块，有少量不规则发梢，不要成束飘扬的写实头发。刘海只用三到五个圆钝尖角概括，轻微左右不对称。细一点的深灰手绘线条，线条稍有自然粗细变化，但干净清楚。眼睛改成小小的半眯眼：两条低平的上眼皮加下方简洁小深色楔形瞳孔，有点困困的、呆萌又无奈的表情，像截图中作业帮和 Gmail 的表情，不要大眼睛、不要虹膜细节、不要眼球高光。嘴巴只有一个很小的猫嘴弧线。腮红只是两笔淡粉色短线。耳机和粉色发夹极简。
风格必须是普通画师画出来的可爱表情包小贴纸：平涂，柔和低饱和青绿（接近 #74BFC2）头发，乳白皮肤，灰黑服装，最多一层平涂阴影。避免 glossy shiny polished anime AI mascot 的质感。不要头发高光斑、玻璃眼、柔光、渐变、体积光、复杂服饰和大幅装饰性发丝。脸不能照搬图1那个大眼睛精致头像，要真正换成截图的简笔表情风格。角色比图1略小、更扁，兔子依然是清楚可辨的 APP 主标志。
输出：单张正方形1024x1024成品图标母版，完全不透明的纯白 #FFFFFF 底，四角也是纯白，不要透明背景，不要棋盘格，不要阴影，不要画图标外框，不要文字。没有手机、桌面、其他APP标志、水印和多版本拼图。保留充足白边，图案整体居中，适合小尺寸安卓桌面图标。
