package cn.xux.cloudworkbench

internal object TechnologyHistory {
    val mechanical = HistoryTrail("从数控铣床到协作机器人", "1952—2010年代", "铣床、机械臂与工人",
        "机床先能按数字指令切削，机械臂再学会重复搬运和感知接触。变化既发生在机器上，也发生在工人与机器分配任务的方式上。", listOf(
            TrailStep("1952年", "纸带指挥铣床切削",
                "麻省理工学院把零件图样转成数字指令，编码在穿孔纸带上。伺服机构依次移动刀具，复杂曲面可以重复加工。",
                TrailSource("MIT 档案馆 · 数控铣床公告", "https://cdn.libraries.mit.edu/dissemination/diponline/AC0069_NewReleases/NewsRelease_1950/AC0069_1952/AC0069_195208_005.pdf")),
            TrailStep("1961年", "Unimate 走进汽车生产线",
                "通用汽车工厂用可编程机械臂搬运热的压铸件。机器接手重复而危险的工序，重新设定程序即可改变动作。",
                TrailSource("国际机器人联合会 · 机器人发展年表", "https://ifr.org/robot-history")),
            TrailStep("1973年", "六轴电动机械臂改变工具姿态",
                "德国库卡的 FAMULUS 使用六个机电驱动轴。工具可以从不同角度接近工件，机械臂的用途随之扩大。",
                TrailSource("库卡 · 自动化发展史", "https://www.kuka.com/en-my/company/about-kuka/history")),
            TrailStep("2010年代", "力传感让人机协作成为设计目标",
                "库卡 LBR iiwa 在关节中加入力矩感知。生产线设计者可以安排机器感知接触、辅助装配，并重新评估人与机器共用空间的条件。",
                TrailSource("库卡 · 企业与产品沿革", "https://www.kuka.com/en-my/company/about-kuka/kuka120"))
        ))

    val chips = HistoryTrail("从一个晶体管到芯片上的处理器", "1947—2019", "晶体管、集成电路与光刻机",
        "电子开关缩小之后，计算机的瓶颈从接线数量转向如何在同一片硅上制造更多器件。", listOf(
            TrailStep("1947年", "点接触晶体管放大信号",
                "贝尔实验室的巴丁和布拉顿在锗上实现信号放大。半导体器件为替代体积大、耗能高的电子管打开了路径。",
                TrailSource("计算机历史博物馆 · 点接触晶体管", "https://www.computerhistory.org/siliconengine/invention-of-the-point-contact-transistor/")),
            TrailStep("1958—1959年", "多个器件被放进同一块材料",
                "基尔比演示集成电路，诺伊斯提出适合平面制造的单片方案。接线逐步转到芯片内部，为批量制造复杂电路创造条件。",
                TrailSource("计算机历史博物馆 · 硅技术年表", "https://www.computerhistory.org/siliconengine/timeline/")),
            TrailStep("1971年", "中央处理功能集成到一枚芯片",
                "英特尔 4004 将处理器的主要运算和控制功能放在单芯片上。设备可以通过更换程序承担不同计算任务。",
                TrailSource("计算机历史博物馆 · 微处理器年表", "https://www.computerhistory.org/siliconengine/timeline/")),
            TrailStep("2019年", "极紫外光刻进入商业芯片量产",
                "芯片制造商采用极紫外光刻印制精细图案。更短波长的光和复杂的光学系统把新的制造能力带入量产。",
                TrailSource("ASML · 2019 年度回顾", "https://www.asml.com/investors/annual-report/2019/highlights"))
        ))

    val science = HistoryTrail("人类如何看到此前观测不到的现象", "1990—2022", "哈勃、粒子探测器、引力波干涉仪与韦布",
        "望远镜接收光，加速器实验重建碰撞产物，干涉仪测量距离变化。不同仪器打开了不同的观测窗口。", listOf(
            TrailStep("1990—1993年", "哈勃升空并在轨修复",
                "哈勃进入地球轨道后暴露出主镜像差。首次维修任务安装校正光学装置，让望远镜发挥原先设计的观测能力。",
                TrailSource("NASA · 哈勃任务年表", "https://science.nasa.gov/mission/hubble/overview/hubble-timeline/hubble-history-timeline-full-text/")),
            TrailStep("2012年", "两组实验报告新粒子",
                "欧洲核子研究中心的 ATLAS 和 CMS 从大量碰撞数据中找到新粒子的信号，其性质与希格斯玻色子的预期相符。",
                TrailSource("CERN · 发现希格斯玻色子的过程", "https://home.cern/science/physics/higgs-boson/how/")),
            TrailStep("2015—2016年", "干涉仪首次直接探测到引力波",
                "LIGO 的两台探测器于2015年记录到双黑洞并合信号，团队在2016年公布结果。天文学由此增加了观测时空扰动的方法。",
                TrailSource("LIGO · 实验年表", "https://www.ligo.caltech.edu/page/timeline")),
            TrailStep("2022年7月", "韦布首批科学图像公开",
                "在地球以外运行的韦布望远镜用红外光观测星系和恒星形成区。光谱还让研究者辨认遥远行星大气中的物质。",
                TrailSource("NASA · 韦布首批科学观测", "https://science.nasa.gov/mission/webb/webbs-first-images/"))
        ))

    val networks = HistoryTrail("从四台主机互联到移动互联网", "1969—2019", "分组网络、TCP/IP、万维网与智能手机",
        "先让计算机彼此交换数据，再让网页通过链接相互连接，最后让人在移动中接入同一个网络。", listOf(
            TrailStep("1969年", "ARPANET 连接四台主机",
                "到年底，四个节点接入早期分组交换网络。不同地点的研究人员开始尝试远程使用计算资源。",
                TrailSource("互联网协会 · 互联网简史", "https://www.internetsociety.org/internet/history-internet/brief-history-internet/")),
            TrailStep("1983年1月", "ARPANET 切换到 TCP/IP",
                "不同网络采用共同的寻址和传输协议，内部结构不同的网络也能相互传递分组。",
                TrailSource("互联网协会 · 协议演化", "https://www.internetsociety.org/internet/history-internet/brief-history-internet/")),
            TrailStep("1989—1993年", "万维网从提案走向公开使用",
                "蒂姆·伯纳斯－李在 CERN 提出通过超文本链接资料。1993年 CERN 开放万维网软件，更多机构可以自行建立服务器、提供网页。",
                TrailSource("CERN · 万维网简史", "https://home.cern/science/computing/the-birth-of-the-web/short-history-web/")),
            TrailStep("2007年", "手机把网页浏览带入移动场景",
                "苹果发布首代 iPhone，将触控电话与互联网通信功能组合起来。用户可以在同一台随身设备上浏览网页、收发邮件。",
                TrailSource("苹果 · 首代 iPhone 发布资料", "https://www.apple.com/uk/newsroom/2007/01/09Apple-Reinvents-the-Phone-with-iPhone/")),
            TrailStep("2019年6月", "中国发放 5G 商用牌照",
                "工信部向四家企业发放第五代数字蜂窝移动通信业务许可，为公众移动网络和企业应用部署新一代无线接入技术提供了制度条件。",
                TrailSource("工信部 · 四家企业获发 5G 牌照", "https://www.nrta.gov.cn/art/2019/6/6/art_114_46091.html"))
        ))

    val ai = HistoryTrail("从达特茅斯研究计划到多模态大模型", "1955—2026", "麦卡锡、神经网络研究者与大模型团队",
        "从提出“机器能否学习”，到用误差训练网络，再到让公众通过对话使用模型。每个节点都说明研究者解决了什么问题、怎样检验结果。", listOf(
            TrailStep("1955—1956年", "达特茅斯研究计划提出人工智能问题",
                "麦卡锡、明斯基、罗切斯特和香农在提案中列出语言、学习与抽象等研究问题。次年夏季，研究者在达特茅斯聚集，人工智能逐渐形成跨学科研究方向。",
                TrailSource("麦卡锡等 · 达特茅斯研究计划原文", "https://www-formal.stanford.edu/jmc/history/dartmouth/dartmouth.html")),
            TrailStep("1986年", "反向传播训练多层神经网络",
                "鲁梅尔哈特、辛顿和威廉斯展示如何把输出误差逐层传回网络、调整连接权重。中间层能从训练样本学出完成任务所需的特征。",
                TrailSource("《自然》 · 反向传播研究论文", "https://www.nature.com/articles/323533a0")),
            TrailStep("2012年", "卷积网络在图像分类中取得突破",
                "多伦多大学团队用 GPU 训练深层卷积网络，在 ImageNet 分类任务上大幅降低错误率。大量标注图像与计算能力共同发挥了作用。",
                TrailSource("多伦多大学 · ImageNet 原始论文", "https://www.cs.toronto.edu/~hinton/absps/imagenet.pdf")),
            TrailStep("2016年3月", "AlphaGo 在五番棋中战胜李世石",
                "系统把神经网络与树搜索结合起来，以4比1赢得比赛。这一结果来自有明确规则和胜负标准的围棋环境。",
                TrailSource("Google DeepMind · AlphaGo 项目", "https://deepmind.google/research/alphago/")),
            TrailStep("2017年", "Transformer 将注意力用于序列建模",
                "研究者在机器翻译任务中验证新的网络结构。它用注意力建立词语之间的联系，也为后来的大型语言模型提供了架构基础。",
                TrailSource("Google Research · Transformer 原始论文", "https://research.google/pubs/attention-is-all-you-need/")),
            TrailStep("2020年", "GPT-3 展示上下文学习",
                "研究团队系统比较不同规模的自回归语言模型。对部分任务，给出几条例子即可改变输出，无需为该任务重新训练参数。",
                TrailSource("研究团队 · GPT-3 论文", "https://arxiv.org/abs/2005.14165")),
            TrailStep("2022年11月", "ChatGPT 开放公众对话测试",
                "OpenAI 将使用人类反馈训练的对话模型开放给公众试用。用户可以追问、要求修正答案，也会遇到模型以肯定语气给出错误信息的问题。",
                TrailSource("OpenAI · ChatGPT 首发说明", "https://openai.com/index/chatgpt/")),
            TrailStep("2025年1月", "DeepSeek-R1 公布推理训练研究",
                "团队报告以强化学习改善数学和代码等任务上的推理表现，并发布模型权重及蒸馏模型，供其他团队检验和复用。",
                TrailSource("DeepSeek · R1 研究报告", "https://arxiv.org/abs/2501.12948")),
            TrailStep("2026年9月", "同一模型接收文字与图像",
                "DeepSeek 发布 V4.1-Flash，介绍其原生视觉理解与非对称编码、解码架构。公开说明把输入处理、生成速度和推理成本分别列出，展示了部署大模型时不同的取舍。",
                TrailSource("DeepSeek · V4.1-Flash 发布记录", "https://api-docs.deepseek.com/news/news260910/"))
        ))

    val topics = listOf("机械制造" to mechanical, "芯片与计算" to chips, "科学仪器" to science, "信息网络" to networks, "AI 与大模型" to ai)
}
