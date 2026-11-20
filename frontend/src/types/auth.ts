export interface UserLoginReqDTO {
  username: string;
  password: string;
}

export interface UserRegisterReqDTO {
  username: string;
  password: string;
  realName?: string;
  phone?: string;
  mail?: string;
}

export interface LoginResult {
  token: string;
  username: string;
  isAdmin: boolean;
}
