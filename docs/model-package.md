# 本地嵌入模型包

APK 不含模型权重。用户从上游下载，并手动导入 ZIP。当前适配中文 BERT WordPiece ONNX 模型，优先考虑量化的小模型。

Windows 用户也可以自行运行项目提供的打包脚本。它优先从 hf-mirror.com 下载固定版本的权重与词表，原站作为后备；支持断点续传并保留 `.part` 文件，核对权重 SHA-256 后生成可导入 ZIP。模型不从本项目分发。本轮没有执行模型下载脚本。

```powershell
& D:\Canxu-project\Xujing\tools\prepare-bge-model.ps1
```

默认输出到 `D:\Roleplay\model-packages`。

## ZIP 结构

```text
model-manifest.json
model.onnx
vocab.txt
```

文件位于 ZIP 根目录，不再套一层目录。也可用 WordPiece `tokenizer.json` 替代 `vocab.txt`。ONNX 若使用外部数据文件，需要一并打包且保持相对路径。所有文件合计不超过 768 MiB，最多 128 项。

`model-manifest.json`：

```json
{
  "id": "bge-small-zh-v1.5-int8",
  "name": "BGE small 中文 v1.5 INT8",
  "modelFile": "model.onnx",
  "tokenizerFile": "vocab.txt",
  "pooling": "cls",
  "normalize": true,
  "queryPrefix": "为这个句子生成表示以用于检索相关文章：",
  "passagePrefix": "",
  "maxTokens": 512,
  "dimensions": 512,
  "language": "zh",
  "sourceUrl": "https://huggingface.co/Xenova/bge-small-zh-v1.5",
  "license": "MIT",
  "sha256": "b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5"
}
```

上面校验值**只对应**以下固定版本的 `onnx/model_int8.onnx`，下载后重命名为 `model.onnx`：

- [ONNX 模型仓库](https://huggingface.co/Xenova/bge-small-zh-v1.5/tree/75c43b069aac4d136ba6bc1122f995fedcfd2781)
- [镜像模型仓库](https://hf-mirror.com/Xenova/bge-small-zh-v1.5/tree/75c43b069aac4d136ba6bc1122f995fedcfd2781)
- [INT8 权重](https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/75c43b069aac4d136ba6bc1122f995fedcfd2781/onnx/model_int8.onnx)，约 23.9 MB
- [vocab.txt](https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/75c43b069aac4d136ba6bc1122f995fedcfd2781/vocab.txt)
- [原模型 BAAI / BGE small zh v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)

ONNX 转换来自社区仓库，原模型来自 BAAI。下载和使用遵循各上游仓库许可。当前尚未测量这组权重在目标手机上的耗时与检索效果。

## 导入与切换

1. 将上述文件压成 ZIP。
2. 设置 → 嵌入模型与记忆 → 导入完整模型 ZIP 包。
3. 选择导入的本地模型并保存。
4. 打开会话，在会话设置中启用长期记忆。
5. 首次生成时补建索引，或手动触发当前会话的重建。

导入后模型 ID 加入权重与分词器的内容指纹。`directory` 由应用填写，无需在清单中提供。

`pooling` 仅接受 `cls` / `mean`；`maxTokens` 8–4096；`dimensions` 为 0 时不固定校验。不同模型必须按其官方模型卡设置前缀和池化方式，不能只替换权重而沿用全部配置。

切换后旧索引独立保留，不与新向量混合。生成前增量补齐索引；手动重建会重新计算当前模型对应的片段，不清除其他模型的索引。服务商更新同名模型后，也可用手动重建刷新向量。备份不包含模型权重和派生索引，可从聊天原文重建。
