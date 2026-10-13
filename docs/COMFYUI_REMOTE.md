# ComfyUI 外网地址配置与连接诊断

手机不在电脑局域网时，应使用穿透服务的对外地址，不能使用电脑的局域网 IP、本地端口或手机上的 localhost。Android 1.6.1 的“设置 → 图像生成引擎 → ComfyUI”支持公网地址和连接诊断；它不会创建隧道或修改 ComfyUI 的服务器安全配置。

## 地址填写

| 连接方式 | APP 地址示例 | 端口含义 |
| --- | --- | --- |
| 同一局域网 | `http://192.168.1.100:8188` | 电脑实际监听端口 |
| TCP 穿透 HTTP | `http://node.example.com:35961` | 穿透服务分配的远程端口，不是电脑本地端口 |
| TCP 穿透 HTTPS | `https://node.example.com:35961` | 自动 HTTPS 或 HTTPS 服务的远程端口，需要受信任且匹配主机的证书 |
| HTTP／HTTPS 域名隧道 | `https://comfy.example.com` | 使用绑定域名及服务约定端口，不能用节点 IP 代替绑定域名 |
| 反向代理子路径 | `https://example.com/comfy` | APP 会保留 `/comfy`，追加各个 API 路由 |

以上均为示例，不是可用服务。裸 `主机:端口` 会按 HTTP 补全，不添加 `8188`；建议始终写明协议。樱花 FRP 对 TCP 与 HTTP(S) 的域名、端口要求不同，配置应与隧道类型一致。[樱花 FRP 官方 Web 穿透指南](https://doc.natfrp.com/app/http.html)

只填写 ComfyUI 服务基址，不追加 `/system_stats`、`/prompt` 或 `/view`，不填写查询参数、网页登录地址、用户名或密码。主机和端口的选择会决定真实请求的 Host，不需要手动改写 Host 头。

输入后可先点击“测试 ComfyUI 连接”，测试使用当前草稿；确认正确后点击“应用地址”。未应用时，生成仍使用之前保存的地址。一次连接成功仅说明 API 可以访问，不证明固定的 z-image-turbo 工作流、模型文件和节点已配置完成。

## 故障提示

- **HTTP 501 且提示自动 HTTPS**：隧道拒绝明文 HTTP，网页可能用 JavaScript 跳转。将地址改为同域名、端口和路径的 `https://` 后重新测试；APP 不执行网页脚本。
- **HTTPS／TLS 失败**：检查证书是否受信任、是否有效、是否匹配地址中的主机。使用 IP 通常不能代替域名证书。樱花自动生成的自签名证书不会被客户端自动信任。
- **HTTP 200 但不是 ComfyUI API**：可能连到了认证页、节点介绍页或错误的代理路径。APP 校验的是 `/system_stats` 数据，不是网页能否打开。[ComfyUI 官方路由实现](https://github.com/Comfy-Org/ComfyUI/blob/master/server.py)
- **HTTP 401／403**：网关需要授权或拒绝访问。当前客户端不支持交互式网页登录和网关凭据，不要通过移除访问保护来解决。
- **HTTP 404**：检查基址和代理子路径，确保 `/system_stats`、`/prompt`、`/history/{id}`、`/view` 都会转发到 ComfyUI。
- **HTTP 502／503／504**：检查 ComfyUI 是否启动、隧道是否在线、本地目标地址与监听端口是否一致。
- **DNS、连接失败或超时**：检查手机网络、远程域名和端口，以及穿透节点的使用限制。

## HTTPS 与安全边界

优先使用受信任 HTTPS 或受保护的私人连接，不要公开无鉴权 ComfyUI，也不要在公网 HTTP 传输敏感提示词或图片。APP 不关闭 TLS 校验、不自动信任自签名证书、不放宽服务器的 Host／Origin 检查。

樱花 FRP 自动 HTTPS 的证书配置属于隧道服务端问题；没有可配置的域名时，本版只能提供地址支持与具体诊断，不能保证该自签名入口直接连通。[自动 HTTPS 官方说明](https://doc.natfrp.com/frpc/auto-https.html)
