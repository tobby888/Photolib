use axum::{routing::get, Router};

#[tokio::main]
async fn main() {
    // 1. 构建路由：当访问根路径 "/" 时，返回 "Hello, Axum!"
    let app = Router::new().route("/", get(|| async { "Hello, Axum!" }));

    // 2. 绑定本地端口（这里使用 3000 端口）
    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000")
        .await
        .unwrap();
    println!("🚀 服务已启动，正在监听: http://127.0.0.1:3000");

    // 3. 启动服务
    axum::serve(listener, app).await.unwrap();
}