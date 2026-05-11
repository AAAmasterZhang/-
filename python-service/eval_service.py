import pandas as pd
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import (
    faithfulness,
    answer_relevancy,
    context_precision,
    context_recall,
)
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper
from langchain_community.chat_models import ChatTongyi
from langchain_community.embeddings import DashScopeEmbeddings
import config_data as config


class RagEvalService:
    def __init__(self):
        # 1. 包装 LLM：使用能力最强的模型作为裁判
        tongyi_llm = ChatTongyi(model_name="qwen-max")
        self.evaluator_llm = LangchainLLMWrapper(tongyi_llm)

        # 2. 包装 Embedding：用于计算 Answer Relevancy
        tongyi_embeddings = DashScopeEmbeddings(model=config.embedding_model_name)
        self.evaluator_embeddings = LangchainEmbeddingsWrapper(tongyi_embeddings)

        # 3. 选择评估指标
        self.metrics = [
            faithfulness,  # 忠实度：回答是否来自上下文
            answer_relevancy,  # 相关性：是否回答了问题
            context_precision,  # 检索精度：正确的片段是否排在前面
            context_recall  # 检索召回：上下文是否覆盖了答案点
        ]

    def run_evaluation(self, eval_samples: list):
        """
        eval_samples 格式：
        [
            {
                "question": "...",
                "answer": "...",
                "contexts": ["doc1...", "doc2..."],
                "ground_truth": "..."
            },
            ...
        ]
        """
        print(f"--- 正在开始评估 {len(eval_samples)} 条样本 ---")

        # 将数据转换为 Ragas 要求的 Dataset 格式
        df = pd.DataFrame(eval_samples)
        dataset = Dataset.from_dict(df.to_dict(orient="list"))

        # 执行评估
        result = evaluate(
            dataset=dataset,
            metrics=self.metrics,
            llm=self.evaluator_llm,
            embeddings=self.evaluator_embeddings
        )

        return result