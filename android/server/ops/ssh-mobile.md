# Android SSH 业务连接

平板使用独立公钥。维护端使用现有 SSH 配置登录。手机公钥存在 `~/.ssh/authorized_keys`，权限为 `restrict,port-forwarding,permitopen="127.0.0.1:8766"`，并使用固定命令签发短期会话令牌。令牌只能从回环业务入口使用。`8766` 服务承载请求、事件和 APK 分段更新。

使用 [SSH 公钥管理工具](ssh-mobile-authorized-key.py) 校验指纹和固定命令。修改前备份 `authorized_keys`，保持 0600 权限。变更后先用独立管理员连接确认维护入口，再从平板验证工作台业务。
