import uvicorn
from fastapi import FastAPI, Body, HTTPException
from pydantic import BaseModel
import os

# ========== 1. 导入你所有的核心模块（路径请确认和你的项目一致） ==========
from rag import RagService  # 你的RagService类
from konwledge_base import KnowledgeBaseService  # 你的向量库上传服务
import config_data as config  # 你的配置文件

# ========== 2. 初始化FastAPI ==========
app = FastAPI(
    title="黑马点评RAG接口",
    version="1.0",
    docs_url="/api/docs"  # Apifox测试入口：http://localhost:8000/api/docs
)

# ========== 3. 全局初始化服务（只初始化一次，提升性能） ==========
try:
    # 初始化RAG问答服务（复用你的RagService）
    rag_service = RagService()
    # 初始化向量库上传服务
    kb_service = KnowledgeBaseService()

except Exception as e:
    raise RuntimeError(f"服务初始化失败：{str(e)}")

# ========== 4. 定义请求体模型（和你的调用逻辑对齐） ==========
# 接口1：RAG问答请求体（对应你main里的{"input": 问题} + session_id）
class ChatRequest(BaseModel):
    input: str  # 用户提问（比如"最安静的餐厅"）
    session_id: str = "default"  # 会话ID（对应你main里的session_config）

# 接口2：通用文本上传请求体（保留原有逻辑）
class UploadRequest(BaseModel):
    file_name: str  # 文件名（比如"restaurant_1001.txt"）
    content: str  # 要上传的文本内容

# ========== 新增：博客上传请求体（适配多评论） ==========
class BlogUploadRequest(BaseModel):
    blog_id: str  # 博客唯一ID（如1001）
    title: str    # 博客标题
    content: str  # 博客内容
    comments: list[str] = []  # 评论列表（支持多条，如["好吃！", "性价比高"]）

# ========== 5. 统一响应模型 ==========
class ApiResponse(BaseModel):
    code: int
    msg: str
    data: object

# ========== 6. 接口1：RAG问答（完全复用你main里的逻辑） ==========
@app.post("/api/rag/chat", response_model=ApiResponse)
def rag_chat(request: ChatRequest = Body(...)):
    try:
        # 1. 构造你main里的session_config（完全复用）
        session_config = {
            "configurable": {
                "session_id": request.session_id,
            }
        }
        # 2. 调用你main里的核心逻辑（一字不差复用）
        res = rag_service.chain.invoke({"input": request.input}, session_config)

        # 3. 返回标准化结果
        return ApiResponse(
            code=200,
            msg="查询成功",
            data={"question": request.input, "answer": res}
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=ApiResponse(code=500, msg=f"查询失败：{str(e)}", data=None).model_dump()
        )

# ========== 7. 接口2：通用文本上传（复用你的upload_by_str，保留原有逻辑） ==========
@app.post("/api/rag/upload", response_model=ApiResponse)
def upload_to_vector(request: UploadRequest = Body(...)):
    try:
        # 调用你原有上传方法
        result = kb_service.upload_by_str(request.content, request.file_name)
        return ApiResponse(
            code=200,
            msg="上传成功",
            data={"file_name": request.file_name, "result": result}
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=ApiResponse(code=500, msg=f"上传失败：{str(e)}", data=None).model_dump()
        )

# ========== 新增：接口3：博客上传（适配多评论） ==========
@app.post("/api/rag/upload_blog", response_model=ApiResponse)
def upload_blog(request: BlogUploadRequest = Body(...)):
    try:
        # 调用新增的博客上传方法
        result = kb_service.upload_blog(
            blog_id=request.blog_id,
            title=request.title,
            content=request.content,
            comments=request.comments
        )
        return ApiResponse(
            code=200,
            msg="博客上传成功",
            data={
                "blog_id": request.blog_id,
                "title": request.title,
                "comment_count": len(request.comments),
                "result": result
            }
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=ApiResponse(code=500, msg=f"博客上传失败：{str(e)}", data=None).model_dump()
        )

# ========== 8. 启动服务 ==========
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)