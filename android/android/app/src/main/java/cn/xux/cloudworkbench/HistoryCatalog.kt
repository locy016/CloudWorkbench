package cn.xux.cloudworkbench

import cn.xux.cloudworkbench.HistoryLens.*
import cn.xux.cloudworkbench.HistoryRegion.*

internal enum class HistoryRegion(val label: String, val scope: String) {
    China("中国", "沿黄河、长江流域及后世中国，观察聚落、国家与社会的变化。"),
    Egypt("尼罗河流域", "以尼罗河下游为中心，从古埃及追踪至当代埃及。"),
    Iraq("两河流域", "沿底格里斯河和幼发拉底河，观察古代城邦、帝国与今伊拉克。"),
    India("南亚", "从今巴基斯坦的印度河城市，延伸至印度次大陆及当代印度。"),
    Britain("不列颠", "从岛上的王国、英格兰议会，追踪至近现代英国。"),
    France("法国及其前身地区", "以今法国所在区域为线索，区分法兰克王国、法国王室与共和国。"),
    Russia("俄罗斯及其前身地区", "沿莫斯科国家、俄罗斯帝国、苏联及俄罗斯联邦观察政权变迁。"),
    Germany("德意志地区", "从中欧德语地区的城市与邦国，追踪至统一后的德国。"),
    Japan("日本列岛", "从绳文聚落到近现代日本，观察列岛社会与对外联系。"),
    America("北美·美国", "从今美国境内的原住民社会，追踪至美国及其跨国联系。"),
    Brazil("巴西", "沿巴西所在地区，观察大西洋贸易、独立运动与当代国际合作。")
}

internal enum class HistoryLens(val label: String) {
    Governance("政权制度"), Economy("经济交流"), Society("社会生活"), Ideas("文化思想"), Technology("科学技术")
}

internal data class HistoryEvent(
    val region: HistoryRegion, val lens: HistoryLens, val date: String, val title: String,
    val detail: String, val reading: String, val url: String, val trail: HistoryTrail? = null
)

internal data class HistoryEra(val name: String, val dates: String, val summary: String,
    val regionalSummaries: Map<HistoryRegion, String>, val events: List<HistoryEvent>)

internal const val HISTORY_STANDARD = "https://phi.history.ucla.edu/nchs/world-history-content-standards/"

internal val historyEras = listOf(
    HistoryEra("聚落与生计", "史前至约公元前4000年", "定居有许多起点。有的村落依靠稻作和畜牧，有的聚落长期依靠森林与海洋。", mapOf(
        Japan to "北日本的绳文人依靠捕鱼、狩猎和采集在水边定居，森林与海岸提供了多种食物。",
        China to "贾湖人用鸟骨制笛；河姆渡的稻谷和木构件则留住了长江下游种稻与建屋的痕迹。",
        India to "梅尔格尔的泥砖储藏设施、谷物和家畜遗存，记录了村落怎样安排食物与手工业。"
    ), listOf(
        HistoryEvent(Japan, Society, "约公元前13000年起", "绳文聚落依水而居", "北日本的沿海与林地为渔猎采集提供了稳定食物。遗址留下居所、墓地与仪式空间，让人看到农业出现以前的定居生活。", "联合国教科文组织 · 北日本绳文遗址", "https://whc.unesco.org/en/list/1632/"),
        HistoryEvent(India, Economy, "新石器时代", "梅尔格尔村落储粮与饲养牲畜", "今巴基斯坦的梅尔格尔发现了泥砖储藏设施、谷物与家畜遗存。沿山口移动的人群逐渐形成聚落，也发展出珠饰等手工业。", "联合国教科文组织 · 梅尔格尔", "https://whc.unesco.org/en/tentativelists/1876/"),
        HistoryEvent(China, Ideas, "距今八千余年", "贾湖人把鹤骨制成吹奏乐器", "河南舞阳出土的骨笛以中空鸟骨钻孔制成，管身还留有划分音孔位置的刻痕。孔距与可吹出的音高，为研究史前音乐提供了实物依据。", "河南博物院 · 贾湖骨笛", "https://www.chnmus.net/ch/collection/treasure/details.html?id=508164979145447651"),
        HistoryEvent(China, Technology, "约公元前5000年", "河姆渡人以木构营造家园", "长江下游湿润的环境保存了木构件、骨耜和稻谷遗存。它们把当地人的建筑技术与稻作生活联系在一起。", "浙江省政府 · 河姆渡遗址博物馆", "https://www.ezhejiang.gov.cn/2017-12/15/c_393331.htm")
    )),
    HistoryEra("城市与早期文明", "约公元前4000—前1000年", "四个河流区域留下了不同的城市印记：从记录粮食的泥板，到调水的堤坝与规划整齐的街巷。", mapOf(
        China to "良渚以稻作和水利支撑城址，玉琮显现出礼仪传统。到晚商，甲骨和陶范分别留下王室问卜与工匠生产的线索。",
        Egypt to "吉萨的金字塔需要组织采石、运输与给养。乌纳斯墓室的铭文和后世工匠的家族墓，让人从王室信仰读到劳动者的生活。",
        Iraq to "乌鲁克人用泥板记下物资收支。乌尔墓葬中的棋盘与琴饰，还保存了城市社会游戏和音乐的形象。",
        India to "哈拉帕文明的城市把砖屋、街道和排水设施连在一起。印章上的动物与尚待释读的符号留下另一类线索。"
    ), listOf(
        HistoryEvent(China, Economy, "约公元前3300—前2300年", "良渚以稻作支撑古城", "太湖流域的稻作聚落修筑堤坝、营建城址。城内外墓葬中不均等的玉器组合，为理解社会分层提供了线索。", "联合国教科文组织 · 良渚古城遗址", "https://whc.unesco.org/en/list/1592/"),
        HistoryEvent(Iraq, Technology, "约公元前3200年", "乌鲁克用泥板记账", "在今伊拉克南部，乌鲁克的大型公共建筑与早期泥板文书出现在同一座城市。记录物资收支的需要，给文字留下了早期的使用场景。", "大都会艺术博物馆 · 乌鲁克", "https://www.metmuseum.org/essays/uruk-the-first-city"),
        HistoryEvent(India, Society, "约公元前2600—前1900年", "印度河城市铺设排水系统", "摩亨佐达罗的砖屋、街道与排水设施展现出协调营建的能力。城市位于今巴基斯坦，属于跨越今天国界的哈拉帕文明。", "联合国教科文组织 · 摩亨佐达罗", "https://whc.unesco.org/en/list/138/"),
        HistoryEvent(India, Ideas, "约公元前2600—前1900年", "印度河印章并置独角兽与刻符", "一枚皂石印章残件把侧身的独角兽形象与一行符号刻在一起。符号尚未得到公认的释读，读者能辨认图像与排列，难以确定持有者的姓名或意图。", "大都会艺术博物馆 · 印度河印章残件", "https://www.metmuseum.org/art/collection/search/324064"),
        HistoryEvent(Iraq, Society, "约公元前2600—前2400年", "乌尔棋盘留下二十个游戏格", "乌尔王墓出土的棋盘以贝壳拼成二十格，其中有花形与圆点图案。木质底板已经腐朽，镶片保留的位置帮助考古人员复原了棋盘。", "大英博物馆 · 乌尔王室棋盘", "https://artsandculture.google.com/asset/the-royal-game-of-ur/MwE2MMZNSKiTwQ?hl=en"),
        HistoryEvent(Egypt, Governance, "约公元前26世纪", "吉萨高原建起王室金字塔", "古王国第四王朝在尼罗河西岸营建王室墓地。采石、运输、给养与施工把各类劳动者组织到同一项长期工程中。", "联合国教科文组织 · 孟菲斯及其墓地", "https://whc.unesco.org/en/list/86/"),
        HistoryEvent(Iraq, Ideas, "约公元前2450年", "乌尔琴饰描绘动物奏乐", "乌尔王墓出土的里拉琴饰板以贝壳镶嵌分层画面，其中一只动物正在弹琴。把这幅图放回琴身上看，乐器本身也是当时人讲述宴饮与音乐的媒介。", "宾夕法尼亚大学博物馆 · 乌尔里拉琴饰板", "https://artsandculture.google.com/asset/lyre-fragment-plaque/YQEURJPNFUUx-Q?hl=en"),
        HistoryEvent(China, Ideas, "约公元前2400年", "良渚玉琮在四角刻出面纹", "这件玉琮外方内圆，四角浅刻眼和口组成的面纹。器形和纹样可以直接观察，其在仪式中的具体含义仍待解释。", "大都会艺术博物馆 · 良渚玉琮", "https://www.metmuseum.org/art/collection/search/72376"),
        HistoryEvent(Egypt, Ideas, "约公元前24世纪", "乌纳斯墓室刻入《金字塔文》", "萨卡拉的乌纳斯金字塔将丧葬咒文刻在内部墙面。铭文旨在帮助逝去的国王进入来世，让王室对死亡的理解留下可读的文字。", "埃及旅游与文物部 · 乌纳斯金字塔", "https://egymonuments.gov.eg/monuments/pyramid-of-unas/"),
        HistoryEvent(China, Ideas, "约公元前13世纪起", "殷墟甲骨留下王室问卜", "晚商王室在龟甲和兽骨上留下关于收成、天气和征伐的记录。甲骨把考古发现与当时人的语言和关切连接起来。", "联合国教科文组织 · 殷墟", "https://whc.unesco.org/en/list/1114/"),
        HistoryEvent(Egypt, Society, "约公元前13世纪", "王墓工匠一家几代做同一门手艺", "在底比斯西岸，孔苏与父亲同为修造王墓的工匠，子孙也进入工队。家族合葬墓留下的姓名和器物，让几代人的职业联系得以辨认。", "大都会艺术博物馆 · 新王国埃及的工匠墓", "https://www.metmuseum.org/essays/an-artisans-tomb-in-new-kingdom-egypt"),
        HistoryEvent(China, Society, "商末周初", "殷墟陶范留下铸铜工匠的工作痕迹", "安阳孝民屯作坊留下了铸造凤鸟纹青铜酒器的陶范。陶范与成器上的细节可以互相比对，至于工匠在政权更替后怎样继续生产，仍有不同解释。", "中国社会科学网 · 从孝民屯到石鼓山", "https://www.cssn.cn/lsx/lsx_kgx/202210/t20221024_5552695.shtml")
    )),
    HistoryEra("古典传统与帝国", "约公元前1000年—公元300年", "大国怎样治理陌生的土地？城墙、石刻和郡县各自记录了统治者组织疆域的办法。", mapOf(
        China to "秦兼并六国后推行郡县、统一度量衡，使中央政令和通行标准进入地方治理。",
        Egypt to "罗马将埃及纳入行省体系，尼罗河谷出产的粮食与税收进入地中海帝国的供应网络。",
        Iraq to "新巴比伦王国以巴比伦为都，城墙、宫殿和巡游大道共同塑造了王权的公共形象。",
        India to "阿育王以多种文字向各地传达诏令；桑奇的佛塔与寺院在一代代增建中成为礼敬空间。"
    ), listOf(
        HistoryEvent(Iraq, Governance, "公元前626—前539年", "巴比伦成为帝国都城", "新巴比伦王国以巴比伦为都。宫殿、城墙与仪式大道既组织了城市空间，也在节庆与巡游中展示王权。", "联合国教科文组织 · 巴比伦", "https://whc.unesco.org/en/list/278/"),
        HistoryEvent(India, Ideas, "公元前3世纪", "阿育王把诏令刻在石上", "孔雀王朝的石柱与摩崖刻文分布在多地。君主关于行为、宽容和治理的主张，以适合当地的语言和文字向外传达。", "印度提交的世界遗产预备名单 · 阿育王敕令遗址", "https://whc.unesco.org/en/tentativelists/6803"),
        HistoryEvent(India, Ideas, "公元前3世纪起", "桑奇佛塔成为佛教礼敬场所", "今印度中部的桑奇陆续建起佛塔、寺院和石柱。后世增建的门楼和雕刻表明，同一处礼敬空间曾被多代社群使用和修整。", "联合国教科文组织 · 桑奇佛教古迹", "https://whc.unesco.org/en/list/524/"),
        HistoryEvent(China, Governance, "公元前221年", "秦统一六国并推行郡县", "秦在兼并各国后推行郡县管理，并统一度量衡等制度。地方行政与中央之间建立起更直接的联系。", "中国国家博物馆 · 古代中国秦汉时期", "https://www.chnmuseum.cn/portals/0/web/zt/gudai/detail4.html"),
        HistoryEvent(Egypt, Economy, "公元前30年", "埃及纳入罗马统治", "公元前30年，罗马将埃及并入帝国。作为行省的埃及既向罗马提供粮食，也贡献了可观的税收，尼罗河谷的收成由此牵动地中海另一端的城市。", "大都会艺术博物馆 · 罗马统治下的埃及", "https://www.metmuseum.org/essays/roman-egypt")
    )),
    HistoryEra("区域交流的扩展", "约300—1000年", "运河、都城和书籍连接远方。观察粮食、工匠与知识如何在不同政权之间流动。", mapOf(
        China to "敦煌石窟见证丝路上的佛教传播，隋代运河连通南北物资运输，唐代女俑则保留了骑马出行的形象。",
        Egypt to "法蒂玛王朝在旧城附近营建开罗，新的宫城逐步与尼罗河沿岸城区相连。",
        Iraq to "巴格达学者借助希腊语、叙利亚语和阿拉伯语之间的翻译，把医学、数学与哲学著作带入新的学术环境。",
        India to "阿旃陀石窟把佛教故事画进礼拜与居住空间，留下德干地区绘画传统的实物。",
        Japan to "奈良成为宫廷与寺院集中的都城；皇室献给东大寺的器物把宫廷生活与佛教仪式联系起来。",
        France to "法兰克国王查理曼在罗马加冕，其帝国横跨今法、德等地，西欧王权与教会的联系加深。"
    ), listOf(
        HistoryEvent(China, Ideas, "公元366年起", "敦煌莫高窟以壁画讲述佛教故事", "敦煌位于丝路交通要地，洞窟中的壁画和塑像把佛教故事留在往来人群经过的绿洲。持续千年的开窟与供养留下了不同时代的图像。", "联合国教科文组织 · 莫高窟", "https://whc.unesco.org/en/list/440/"),
        HistoryEvent(India, Ideas, "公元5—6世纪", "阿旃陀以石窟壁画描绘佛教叙事", "印度德干高原的阿旃陀在早期洞窟之后又开凿了一批佛教石窟。壁画和雕塑把故事安排在礼拜或居住空间中，是观察古印度绘画的重要材料。", "联合国教科文组织 · 阿旃陀石窟", "https://whc.unesco.org/en/list/242/"),
        HistoryEvent(China, Economy, "605年起", "隋代扩建大运河", "隋代分段开凿和整治水道，连接南北水系。江淮粮食和物资得以沿水路运往北方政治中心。", "联合国教科文组织 · 大运河", "https://whc.unesco.org/en/list/1443/"),
        HistoryEvent(China, Society, "唐代", "骑马女俑留下唐代女性衣着的形象", "一件施三彩釉的陶俑把女子与坐骑塑为一体，骑姿与衣着都清晰可见。随葬人像保存了特定阶层塑造出行形象的方式。", "大都会艺术博物馆 · 唐代骑马女俑", "https://www.metmuseum.org/art/collection/search/42379"),
        HistoryEvent(Japan, Governance, "710年", "平城京成为都城", "奈良的新都汇集宫城、寺院和官署。街区规划与建筑既吸收东亚大陆经验，也形成列岛自身的政治和宗教中心。", "联合国教科文组织 · 古都奈良", "https://whc.unesco.org/en/list/870/"),
        HistoryEvent(Japan, Ideas, "756年", "光明皇后把圣武天皇遗物献给东大寺", "圣武天皇去世后，光明皇后将其生前所用的器物献给东大寺。正仓院保存的这一批器物，将奈良宫廷的物质生活与佛教追荐联系起来。", "东京国立博物馆 · 正仓院宝物", "https://www.tnm.jp/modules/r_free_page/index.php?id=1968&lang=en"),
        HistoryEvent(France, Governance, "800年", "查理曼在罗马加冕", "法兰克国王接受皇帝冠冕。这个横跨今法国、德国等地的帝国，加深了西欧王权与罗马教会的联系。", "大都会艺术博物馆 · 中欧公元500至1000年", "https://82nd-and-fifth.metmuseum.org/toah/ht/06/euwc.html"),
        HistoryEvent(Iraq, Ideas, "9世纪", "巴格达学者译介希腊典籍", "在阿拔斯王朝的都城，学者把医学、数学和哲学作品译成阿拉伯语。叙利亚语等中介语言与宫廷资助共同推动了译书活动。", "斯坦福哲学百科 · 阿拉伯哲学的希腊文献来源", "https://plato.stanford.edu/entries/arabic-islamic-greek/"),
        HistoryEvent(Egypt, Governance, "969年", "法蒂玛王朝兴建开罗", "法蒂玛军队进入埃及后建立新的宫城。宫城、旧城与沿尼罗河的商业区逐步形成更大的城市群。", "大都会艺术博物馆 · 法蒂玛时期的艺术", "https://www.metmuseum.org/essays/the-art-of-the-fatimid-period-909-1171")
    )),
    HistoryEra("半球贸易与交往", "约1000—1500年", "城市、商路和港口织成更密的网络。北美的土丘城市与欧亚商镇，也可以放在同一时间尺度下观察。", mapOf(
        China to "四川商人以纸质交子解决铁钱携带不便的问题，北宋随后将发行纳入官府管理。",
        Egypt to "开罗犹太社群的嫁妆清单把衣物与家产逐项写入文书，保存了城市家庭筹办婚事的细节。",
        Iraq to "1258年巴格达陷落，当地阿拔斯哈里发政权终结，两河地区的政治与财政联系重新组合。",
        Britain to "英格兰的征收与司法争议促成《大宪章》，条款在重颁过程中成为讨论君权边界的依据。",
        France to "沙特尔主教座堂把宗教人物和故事排进彩绘玻璃，使进入教堂的人能借光线观看叙事。",
        Germany to "吕贝克等汉萨商镇通过海运交换货物，城市之间为共同的商业利益协商互助。",
        America to "密西西比文化人群在卡霍基亚营建土丘、广场与居住区，形成北美内陆的城市中心。"
    ), listOf(
        HistoryEvent(China, Economy, "11世纪初", "四川商人使用交子", "铁钱沉重，商人使用可兑换的纸质凭证。北宋随后接管发行，使地方商业实践进入官方货币管理。", "何平 · 传统中国纸币的发展路径", "https://www.nopss.gov.cn/n1/2024/0122/c219544-40163919.html"),
        HistoryEvent(Egypt, Society, "法蒂玛时期，约11—12世纪", "开罗婚嫁清单逐项登记嫁妆", "开罗文书库保存的犹太新娘嫁妆清单，把衣物和家庭财物写入婚嫁文书。研究者由这些具体物件观察家庭如何安排婚事与财产。", "《犹太研究评论》 · 法蒂玛时期的三份嫁妆清单", "https://www.cambridge.org/core/journals/ajs-review/article/abs/three-trousseaux-of-jewish-brides-from-the-fatimid-period/2470B5BF9E7176246B4D9B2114645592"),
        HistoryEvent(America, Society, "约1050年起", "卡霍基亚聚落迅速扩展", "在今伊利诺伊州，密西西比文化人群建造大型土丘、广场和居住区。当地的城市生活比欧洲殖民者抵达早了数百年。", "联合国教科文组织 · 卡霍基亚", "https://whc.unesco.org/en/list/198/"),
        HistoryEvent(France, Ideas, "12—13世纪", "沙特尔以彩窗讲述宗教故事", "沙特尔主教座堂把人物与故事排入一块块彩色玻璃。光线从窗外透过时，建筑本身成了观看故事的场所。", "沙特尔主教座堂 · 彩绘玻璃窗", "https://www.cathedrale-chartres.org/en/cathedrale/monument/the-stained-glass-windows/"),
        HistoryEvent(Britain, Governance, "1215年", "英格兰国王接受《大宪章》", "约翰王与反叛贵族达成协议，对征收与司法作出限制。经过多次重颁，部分条款成为后世讨论君权边界的依据。", "英国议会 · 《大宪章》", "https://www.parliament.uk/about/living-heritage/evolutionofparliament/originsofparliament/birthofparliament/overview/magnacarta/"),
        HistoryEvent(Iraq, Governance, "1258年", "蒙古军队攻陷巴格达", "巴格达的阿拔斯哈里发政权终结。两河地区转入新的帝国网络，城市的政治地位和财政联系随之改变。", "大都会艺术博物馆 · 伊尔汗国时期的艺术", "https://www.metmuseum.org/essays/the-art-of-the-ilkhanid-period-1256-1353"),
        HistoryEvent(Germany, Economy, "14世纪", "吕贝克连接汉萨商镇", "以吕贝克等城市为枢纽，商人跨越波罗的海与北海交易。共同的商业利益促成城市之间的协商和互助。", "联合国教科文组织 · 汉萨城吕贝克", "https://whc.unesco.org/en/list/272/")
    )),
    HistoryEra("跨洋联系的形成", "约1450—1770年", "远洋航路把各洲的市场拉近，也带来殖民征服、奴隶贸易与新的社会分化。", mapOf(
        China to "来自日本和美洲的白银进入明代市场，海上贸易与国内赋税折银相互联系。",
        India to "巴布尔在帕尼帕特获胜，莫卧儿王朝在北印度立足；此后对疆域的控制仍经历争夺。",
        Britain to "1689年《权利法案》限制王室自行征税和维持常备军，进一步确立议会在国家治理中的地位。",
        France to "《百科全书》把作坊手艺纳入知识分类，用文字和图版向读者展示工具与操作。",
        Russia to "莫斯科大公采用沙皇头衔，随后沿伏尔加河流域扩张，将更多地区纳入统治。",
        Japan to "德川幕府以江户为中枢；城市娱乐兴盛，浮世绘木版画把演员等题材带给买画人。",
        Brazil to "沿海蔗糖生产依赖强迫劳动与奴隶贸易；巴伊亚非洲裔社群在节庆聚会中发展圆圈桑巴。"
    ), listOf(
        HistoryEvent(Brazil, Economy, "16世纪", "巴西沿海扩展蔗糖种植园", "葡萄牙殖民者在沿海建立种植园和糖坊。对外贸易的增长与强迫原住民劳动、跨大西洋贩运非洲奴隶紧密相连。", "布朗大学图书馆 · 巴西的商站与糖坊", "https://library.brown.edu/create/fivecenturiesofchange/chapters/chapter-1/feitorias-and-engenhos/"),
        HistoryEvent(India, Governance, "1526年", "莫卧儿王朝在北印度建立", "巴布尔在帕尼帕特击败以德里为中心的洛迪王朝，开启莫卧儿王朝的统治。此后北印度的控制权仍经历争夺，王朝的版图逐步形成。", "大都会艺术博物馆 · 南亚1400至1600年", "https://82nd-and-fifth.metmuseum.org/toah/ht/08/ssa.html"),
        HistoryEvent(Russia, Governance, "1547年", "伊凡四世加冕为沙皇", "莫斯科大公伊凡四世在1547年加冕，正式采用沙皇头衔。在他的统治下，喀山和阿斯特拉罕被并入，莫斯科的统治范围沿伏尔加河扩大。", "卢浮宫 · 伊凡四世与沙皇权力", "https://mini-site.louvre.fr/sainte-russie/EN/html/1.6.html"),
        HistoryEvent(China, Economy, "16世纪后期", "白银流入明代市场", "日本及美洲出产的白银经海上贸易进入中国。商人的跨海交易与赋税折银的趋势交织，加深了地方市场对外部供给的联系。", "哥伦比亚大学 · 明清中国与世界白银贸易", "https://afe.easia.columbia.edu/tps/1450_cn.htm"),
        HistoryEvent(Japan, Governance, "1603年", "德川幕府在江户建立", "德川家康受任征夷大将军，江户成为幕府中枢。大名领地与幕府并存，城镇和交通体系在这一格局中发展。", "大都会艺术博物馆 · 日本1600至1800年", "https://www.metmuseum.org/de/toah/ht/09/eaj.html"),
        HistoryEvent(Japan, Ideas, "17世纪起", "浮世绘木版把城市娱乐带给买画人", "日本出版者用木版成批印制歌舞伎演员等人气题材。城市观众可以购买和收藏同一图像的不同印本。", "大都会艺术博物馆 · 浮世绘木版画", "https://www.metmuseum.org/essays/woodblock-prints-in-the-ukiyo-e-style"),
        HistoryEvent(Brazil, Society, "17世纪起", "巴伊亚居民围成圆圈跳桑巴", "巴伊亚雷孔卡沃地区的圆圈桑巴将歌唱、拍手和轮流入圈的舞蹈结合起来。这一传统与非洲裔社群的聚会和节庆相连。", "联合国教科文组织 · 巴伊亚的圆圈桑巴", "https://ich.unesco.org/en/RL/samba-de-roda-of-reconcavo-of-bahia-00101"),
        HistoryEvent(Britain, Governance, "1689年", "英格兰议会确立《权利法案》", "法案规定王室征税和维持常备军等行为须经议会同意。王位继承与议会权力由此有了新的制度安排。", "英国议会 · 1689年《权利法案》", "https://www.parliament.uk/about/living-heritage/evolutionofparliament/parliamentaryauthority/revolution/collections1/collections-glorious-revolution/billofrights/"),
        HistoryEvent(France, Ideas, "1751年起", "法国《百科全书》把手艺纳入知识分类", "狄德罗与达朗贝尔主持的《百科全书》既编写文字条目，也刊印工艺图版。读者可以对照工具、动作与文字解释，理解作坊如何工作。", "芝加哥大学 ARTFL · 法国《百科全书》数字版", "https://encyclopedie.uchicago.edu/")
    )),
    HistoryEra("工业化与政治革命", "约1750—1914年", "工厂改变了生产的地点，革命与独立运动重新定义了谁能以国家的名义作出决定。", mapOf(
        China to "《南京条约》规定五口通商，沿海城市被纳入列强施加的新贸易条件。",
        Egypt to "苏伊士运河连通地中海和红海，埃及成为欧洲至印度洋航运的重要节点。",
        Britain to "改进后的蒸汽机从矿井抽水扩展到驱动工厂机械，工业生产获得了新的动力来源。",
        France to "革命时期的宣言阐述国民主权与个人权利；近一个世纪后，初等教育法规定男女儿童均须受教育。",
        Germany to "多个德意志邦国纳入以普鲁士为主导的帝国，国家统一采用联邦结构。",
        Japan to "明治政府重组地方治理、军队与学校；富冈女工的机械缫丝训练展示了改革如何走进工厂。",
        America to "十三个殖民地宣布独立。此后新英格兰纺织业兴起，洛厄尔女工在工资劳动与集体寄宿中生活。",
        Brazil to "脱离葡萄牙后，巴西采取君主制，佩德罗成为皇帝，帝国延续至1889年。"
    ), listOf(
        HistoryEvent(Britain, Technology, "18世纪后期", "蒸汽动力逐渐进入工厂", "瓦特改进蒸汽机，提高了燃料利用效率。随后能输出旋转运动的蒸汽机可以驱动工厂机械，蒸汽动力的用途超出了矿井抽水。", "英国科学博物馆 · 詹姆斯·瓦特与蒸汽机", "https://blog.sciencemuseum.org.uk/remembering-james-watt/"),
        HistoryEvent(America, Governance, "1776年", "十三个殖民地宣布独立", "大陆会议通过《独立宣言》，公开陈述脱离英国的理由。战争仍在继续，美国的联邦制度在此后逐步形成。", "美国国家档案馆 · 《独立宣言》", "https://www.archives.gov/milestone-documents/declaration-of-independence"),
        HistoryEvent(France, Ideas, "1789年", "法国通过《人权和公民权宣言》", "法国制宪议会通过宣言，将自由、财产和安全列为人的权利，并指出主权源自国民。这份文本也为后来争论谁能享有这些权利提供了依据。", "法国国民议会 · 1789年《人权和公民权宣言》", "https://www.assemblee-nationale.fr/dyn/histoire-et-patrimoine/revolution-francaise/la-declaration-des-droits-de-l-homme-et-du-citoyen"),
        HistoryEvent(Brazil, Governance, "1822年", "巴西宣布独立", "佩德罗于1822年宣布巴西脱离葡萄牙，随后成为巴西皇帝。新国家在独立后采取君主制度，帝国延续到1889年。", "美国国会图书馆 · 巴西帝国", "https://guides.loc.gov/brazil-us-relations/empire-of-brazil"),
        HistoryEvent(America, Society, "19世纪上半叶", "洛厄尔纺织女工在厂区工作和寄宿", "来自新英格兰乡村的年轻女性进入洛厄尔的纺织厂，许多人住进公司安排的宿舍。工资收入与严格的厂规同时进入她们的日常生活。", "美国国家公园管理局 · 洛厄尔的纺织女工", "https://www.nps.gov/lowe/learn/historyculture/the-mill-girls-of-lowell.htm"),
        HistoryEvent(China, Economy, "1842年", "《南京条约》规定五口通商", "第一次鸦片战争后，清政府被迫与英国签订条约。广州、厦门、福州、宁波和上海被列为通商口岸，沿海贸易格局由此改变。", "故宫博物院 · 《南京条约》", "https://www.dpm.org.cn/lemmas/241863.html"),
        HistoryEvent(Japan, Governance, "1868年", "明治维新开始", "新政府以天皇名义重组政权，随后推动废藩置县、征兵与兴办学校。地方治理和日常生活都受到影响。", "哥伦比亚大学 · 明治维新与近代日本", "https://afe.easia.columbia.edu/special/japan_1750_meiji.htm"),
        HistoryEvent(Egypt, Economy, "1869年", "苏伊士运河通航", "运河连通地中海与红海，缩短欧洲至印度洋的海运航程。埃及在国际航运与列强竞争中的位置随之改变。", "苏伊士运河管理局 · 运河历史", "https://www.suezcanal.gov.eg/English/About/SuezCanal/Pages/CanalHistory.aspx", HistoryTrails.suez),
        HistoryEvent(Germany, Governance, "1871年", "德意志帝国成立", "普鲁士国王在凡尔赛获拥立为德意志皇帝。多个邦国纳入联邦结构，普鲁士在新国家中居于主导地位。", "凡尔赛宫 · 1871年德意志帝国宣告成立", "https://en.chateauversailles.fr/discover/history/key-dates/proclamation-german-empire-1871"),
        HistoryEvent(Japan, Society, "1872年起", "富冈制丝厂招收女工学习机械缫丝", "在富冈，女工随法国女教师学习操作缫丝设备。厂区保留下来的教师住宅、缫丝车间和后来改作宿舍的建筑，记录了生产与生活怎样共处一地。", "富冈市 · 富冈制丝厂建筑", "https://www.tomioka-silk.jp/_tomioka-silk-mill/about/building.html"),
        HistoryEvent(France, Society, "1882年", "法国法律规定儿童接受初等教育", "1882年教育法规定六至十三岁的男女儿童均须受教育，可在学校或家庭完成。法条区分了受教育的义务和接受教育的地点。", "法国国民议会 · 1882年初等教育法原文", "https://www.assemblee-nationale.fr/histoire/loiferry/loi.pdf")
    )),
    HistoryEra("世界战争与社会危机", "约1900—1945年", "革命、战争与经济萧条改变了政治秩序。各地的民众也以救济、抗争和组织动员回应危机。", mapOf(
        China to "武昌起义引发多省响应，临时政府成立与清帝退位使共和制度取代帝制。",
        India to "围绕食盐的非暴力抗争让殖民税制成为公共议题，也把更多民众带入独立运动。",
        Britain to "战时配给限制家庭购买肉类、黄油和糖等食品，普通人的采购由配给簿记录。",
        France to "一战中的法国北部经历占领、物资征集与人口流离，前线与后方生活长期受到影响。",
        Russia to "二月革命结束沙皇统治，十月革命中布尔什维克取得政权，战争与土地问题贯穿两次革命。",
        Germany to "纳粹统治从排斥、驱逐发展到系统性杀戮，约六百万犹太人在大屠杀中遇害。",
        America to "哈莱姆的作家与音乐家讲述非裔城市经验；大萧条后，新政以救济、监管和公共工程应对危机。"
    ), listOf(
        HistoryEvent(China, Governance, "1911—1912年", "辛亥革命与清帝退位", "武昌起义后，多省宣布独立。中华民国临时政府成立与清帝退位，标志着中国国家制度的一次转折。", "美国国务院历史办公室 · 1911年中国革命", "https://history.state.gov/milestones/1899-1913/chinese-rev", HistoryTrails.republic),
        HistoryEvent(France, Society, "1914年", "第一次世界大战波及法国北部", "德军经比利时向法国推进。西线的阵地战持续多年，动员、流离失所和长期的物资征集改变了前线及后方社会。", "詹姆斯·康诺利 · 一战中法国北部的占领生活", "https://discovery.ucl.ac.uk/id/eprint/10048745/1/James%20Connolly%20-%20The%20Experience%20of%20Occupation%20in%20the%20Nord%20-%20Open%20Access.pdf"),
        HistoryEvent(Russia, Governance, "1917年", "俄国发生两次革命", "二月革命终结沙皇统治，十月革命中布尔什维克夺取政权。战争、土地分配和工人生活贯穿政治冲突。", "马克思纪念图书馆 · 1917年俄国革命", "https://www.marx-memorial-library.org.uk/project/centenary-russian-revolution/brief-history-russian-revolution"),
        HistoryEvent(America, Ideas, "20世纪20年代", "哈莱姆的作家与音乐家重塑非裔表达", "纽约哈莱姆聚集了一批非裔美国作家、艺术家和音乐家。诗作、演出与刊物为他们讲述迁徙经历、城市生活与族群身份提供了公共空间。", "美国国会图书馆 · 哈莱姆文艺复兴史料选", "https://www.loc.gov/classroom-materials/harlem-renaissance/"),
        HistoryEvent(India, Society, "1930年", "食盐进军挑战殖民盐税", "甘地及追随者步行抵达海岸制盐。围绕日常必需品的非暴力抗争，吸引了更广泛的民众参与独立运动。", "甘地遗产门户 · 丹迪食盐进军", "https://www.gandhiheritageportal.org/gandhi_heritage_site/writeup/MzY%3D", HistoryTrails.gandhi),
        HistoryEvent(America, Economy, "1933年起", "美国推行新政", "在大萧条期间，联邦政府通过公共工程、银行监管与救济计划应对失业和金融危机。", "罗斯福总统图书馆 · 新政百日展览指南", "https://www.fdrlibrary.org/documents/356632/390886/actionguide.pdf"),
        HistoryEvent(Germany, Society, "1933—1945年", "纳粹迫害与犹太人大屠杀", "纳粹德国及其合作者逐步将犹太人排除出公共生活，继而实施驱逐和系统性杀戮。约六百万犹太人遇害。", "美国大屠杀纪念馆 · 大屠杀概述", "https://encyclopedia.ushmm.org/content/en/article/introduction-to-the-holocaust", HistoryTrails.diary),
        HistoryEvent(Britain, Society, "1941年", "伦敦家庭凭配给簿购买日常食品", "伦敦北部霍恩西签发的配给簿列出肉类、黄油和糖等限量食品。一本用过的簿册，保存了战争如何改变日常采购的细节。", "英国科学博物馆集团 · 英格兰配给簿藏品", "https://collection.sciencemuseumgroup.org.uk/objects/co154306/used-ration-book-and-two-ration-book-supplements-england-1939-1941-ration-book")
    )),
    HistoryEra("战后与当代世界", "1945年至今", "从民族独立、冷战与欧洲重组，到太空探索和气候谈判，国家之间的竞争与合作并行展开。", mapOf(
        China to "改革开放改变农村经营与对外经济联系；数十年后，嫦娥六号带回首批月球背面样品。",
        Egypt to "苏伊士运河公司国有化引发国际危机，运河控制权成为去殖民化进程中的争议焦点。",
        Iraq to "巴比伦列入世界遗产，现代伊拉克面对考古遗迹、历次重建与保护工作并存的历史景观。",
        India to "分治与独立伴随大规模迁徙。后来的学校膳食计划与月船三号任务分别展示公共教育和航天探索的变化。",
        Britain to "《贝尔法斯特协议》为北爱尔兰权力分享及跨境合作建立制度框架。",
        France to "《世界人权宣言》和气候协定先后在巴黎通过，城市见证了两次不同议题的多边协商。",
        Russia to "苏联解体后，俄罗斯联邦延续其联合国会员资格及安理会席位。",
        Germany to "1990年民主德国加入联邦德国，冷战期间形成的两德分立格局结束。",
        Japan to "东海道新干线开通并不断提速，东京与大阪之间的往来时间缩短。",
        America to "从旧金山签署《联合国宪章》、阿波罗登月到北美三国合办世界杯，美国参与了外交、科技和体育领域的跨国活动。",
        Brazil to "贝伦承办联合国气候大会，为围绕资金、适应和气候行动的国际谈判提供会场。"
    ), listOf(
        HistoryEvent(America, Governance, "1945年", "《联合国宪章》在旧金山签署", "来自不同地区的代表在美国旧金山签署宪章。宪章于同年10月生效，联合国正式成立，为战后国际合作建立了常设机构。", "联合国 · 宪章", "https://www.un.org/en/about-us/un-charter"),
        HistoryEvent(India, Governance, "1947年", "英属印度分治并独立", "印度与巴基斯坦成为两个独立自治领。新边界伴随大规模迁徙与暴力，长期影响南亚的国家关系。", "英国国家档案馆 · 英属印度分治", "https://www.nationalarchives.gov.uk/education/teaching-resources/partition-of-british-india/"),
        HistoryEvent(France, Ideas, "1948年", "联合国大会在巴黎通过《世界人权宣言》", "宣言把平等、教育和参与文化生活等权利写入同一份文本。巴黎是通过地，起草和表决的参与者来自多个国家。", "联合国 · 《世界人权宣言》原文", "https://www.un.org/en/about-us/universal-declaration-of-human-rights"),
        HistoryEvent(Egypt, Economy, "1956年", "埃及将苏伊士运河公司国有化", "纳赛尔宣布国有化后，英、法、以色列对埃及采取军事行动。运河的控制权成为去殖民化与冷战外交的交汇点。", "美国国务院历史办公室 · 1956年苏伊士危机", "https://history.state.gov/milestones/1953-1960/suez", HistoryTrails.suez),
        HistoryEvent(Japan, Technology, "1964年", "东海道新干线开通", "东海道新干线在东京和新大阪之间开通。新线开业时全程约需四小时，此后提速继续缩短两地间的旅程。", "东海旅客铁道株式会社 · 公司沿革", "https://global.jr-central.co.jp/en/company/ir/annualreport/_pdf/annualreport2024-03.pdf"),
        HistoryEvent(America, Technology, "1969年", "阿波罗11号载人登月", "两名宇航员在月面活动并采回样品，一名宇航员留在月球轨道。太空竞赛也为月球带回了可在地球实验室研究的实物。", "美国国家航空航天局 · 阿波罗11号任务", "https://www.nasa.gov/missions/apollo/apollo-11/apollo-11-mission-overview/", HistoryTrails.apollo),
        HistoryEvent(China, Economy, "1978年起", "中国开启改革开放", "中共十一届三中全会后，农村经营方式和对外经济政策陆续调整。经济特区随后成为引入外资和试验制度的窗口。", "国家发展改革委 · 中国经济体制改革的历史进程", "https://www.ndrc.gov.cn/fggz/tzgg/byggdt/200606/t20060605_1021728_ext.html"),
        HistoryEvent(Germany, Governance, "1990年", "德国实现统一", "民主德国人民议院于8月表决通过加入联邦德国的决定，10月3日生效。两德统一由此有了明确的法律日期。", "德国联邦议院 · 民主德国人民议院的加入决议", "https://www.bundestag.de/dokumente/textarchiv/1990-08-23-volkskammer-ddr-beitritt-202398"),
        HistoryEvent(Russia, Governance, "1991年", "苏联解体", "苏联解体之际，俄罗斯总统于12月24日致函联合国秘书长，通知俄罗斯联邦将延续苏联的会员资格，包括安理会席位。联合国保存了相关函件。", "联合国 · 1991年12月24日会员资格函件", "https://ask.un.org/faq/378083"),
        HistoryEvent(India, Society, "1995年起", "印度把学校膳食纳入全国营养支持计划", "印度中央政府启动面向小学生的营养支持计划，将改善营养与提高入学、出勤和留校率联系起来。计划在各地已有供餐经验的基础上扩大。", "印度政府新闻局 · 儿童营养与学校膳食计划", "https://static.pib.gov.in/WriteReadData/specificdocs/documents/2022/sep/doc2022915104101.pdf"),
        HistoryEvent(Britain, Governance, "1998年", "《贝尔法斯特协议》达成", "英国、爱尔兰两国政府与北爱尔兰主要政治力量促成协议，为权力分享及跨境合作提供制度框架。", "英国北爱尔兰事务部 · 《贝尔法斯特协议》", "https://www.gov.uk/government/publications/the-belfast-agreement"),
        HistoryEvent(France, Governance, "2015年", "各方在巴黎通过气候协定", "联合国气候变化大会在巴黎通过《巴黎协定》。各方以自主贡献提出减排目标，并按约定周期报告和更新行动。", "联合国气候变化框架公约 · 巴黎协定", "https://unfccc.int/process-and-meetings/the-paris-agreement", HistoryTrails.climate),
        HistoryEvent(Iraq, Ideas, "2019年", "巴比伦列入世界遗产", "伊拉克的巴比伦遗址获列入世界遗产名录。考古遗迹、近现代重建与保护工作叠加在同一处历史景观中。", "联合国教科文组织 · 巴比伦", "https://whc.unesco.org/en/list/278/"),
        HistoryEvent(India, Technology, "2023年", "月船三号在月球南部高纬着陆", "着陆器于8月23日实现软着陆，巡视器随后探测着陆点附近的月面。印度由此获得该区域的原位观测资料。", "印度空间研究组织 · 月船三号", "https://www.isro.gov.in/Release_of_Chandrayaan-3_science_data_to_the_Scientific_Community.html"),
        HistoryEvent(China, Technology, "2024年", "嫦娥六号带回月背样品", "返回器于6月25日带回在月球背面采集的样品。研究者首次能够在地球实验室直接分析来自月背的材料。", "国家航天局 · 嫦娥六号样品交接", "https://www.cnsa.gov.cn/n6758823/n6758844/n10518102/n10518147/c10567320/content.html", HistoryTrails.changeSix),
        HistoryEvent(Brazil, Governance, "2025年", "贝伦承办联合国气候大会", "各方在巴西贝伦举行第三十届联合国气候变化大会，围绕气候行动的落实、资金和适应问题展开谈判。", "联合国气候变化框架公约 · 贝伦会议成果", "https://unfccc.int/cop30/belem-political-package", HistoryTrails.climate),
        HistoryEvent(America, Society, "2026年", "北美三国合办世界杯", "加拿大、墨西哥和美国共同举办男子足球世界杯。参赛队扩大至48支，赛事分布于三国的16座城市。", "国际足联 · 2026年世界杯", "https://www.fifa.com/en/tournaments/mens/worldcup/canadamexicousa2026/articles/fifa-world-cup-2026-hosts-cities-dates-usa-mexico-canada", HistoryTrails.worldCup)
    ))
)
