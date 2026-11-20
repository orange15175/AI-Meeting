import { http } from "./http";
import type { LoginResult, UserLoginReqDTO, UserRegisterReqDTO } from "@/types/auth";

export const authApi = {
  login: (data: UserLoginReqDTO) => http.post<LoginResult>("/ai-meeting/v1/users/login", data),
  register: (data: UserRegisterReqDTO) =>
    http.post<void>("/ai-meeting/v1/users/register", data),
  checkLogin: () =>
    http.get<{ isLogin: boolean; username?: string; token?: string }>(
      "/ai-meeting/v1/users/check-login",
    ),
  logout: () => http.post<void>("/ai-meeting/v1/users/logout"),
};
