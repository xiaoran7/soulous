/**
 * 【测试环境初始化】
 * 导入 @testing-library/jest-dom 的 Vitest 适配版本，
 * 为 Vitest 测试用例注入自定义 DOM 匹配器（如 toBeInTheDocument、toHaveTextContent 等），
 * 使断言更语义化、更易读。
 */
import '@testing-library/jest-dom/vitest';
