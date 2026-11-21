// 自测 ctx.progress 与 ctx.askUser（宿主修复：中间状态只在生成流程协程发出；等待回答不计入调用超时）
// 运行时没有 setTimeout 时不等待（进度会很快刷过）
const sleep = (ms) => (typeof setTimeout === "function" ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve());

rikka.registerTool({
  name: "askuser_selftest",
  description: "自测工具：先汇报几步进度，然后向用户提问并等待回答，最后返回用户的回答。用户要求测试插件提问/进度时调用。",
  parameters: { type: "object", properties: {} },
  systemPrompt: "当用户要求测试插件的进度或提问功能时，调用 askuser_selftest 工具。",
  execute: async (args, ctx) => {
    const t0 = Date.now();
    for (let i = 1; i <= 3; i++) {
      await ctx.progress([{ type: "text", text: `进度 ${i}/3：准备中…` }]);
      await sleep(700);
    }
    await ctx.progress([{ type: "text", text: "等待你回答问题（可以等 30 秒以上再答）…" }]);
    const askedAt = Date.now();
    const answer = await ctx.askUser({
      question: "自测：选一个环境（可以等超过 30 秒再回答）",
      options: ["staging", "prod"],
      placeholder: "也可以输入其他内容",
    });
    const waited = Math.round((Date.now() - askedAt) / 1000);
    await ctx.progress([{ type: "text", text: `收到回答：${answer}，收尾中…` }]);
    await sleep(500);
    rikka.log.info(`askuser_selftest: answer=${answer}, waited=${waited}s, total=${Math.round((Date.now() - t0) / 1000)}s`);
    return `用户回答：${answer}（等待回答 ${waited} 秒；进度汇报 4 次均成功）`;
  },
});
