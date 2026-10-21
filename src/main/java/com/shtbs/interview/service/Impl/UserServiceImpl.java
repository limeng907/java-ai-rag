package com.shtbs.interview.service.Impl;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.dto.UserCreateDTO;
import com.shtbs.interview.dto.UserLoginDTO;
import com.shtbs.interview.dto.UserRegisterDTO;
import com.shtbs.interview.dto.UserUpdateDTO;
import com.shtbs.interview.entity.User;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.mapper.UserMapper;
import com.shtbs.interview.knowledge.KnowledgeRepository;
import com.shtbs.interview.service.EmailService;
import com.shtbs.interview.service.UserService;
import com.shtbs.interview.utils.JwtUtil;
import com.shtbs.interview.vo.LoginVO;
import com.shtbs.interview.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final KnowledgeRepository knowledgeRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final Logger log =
            LoggerFactory.getLogger(QuestionServiceImpl.class);

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil,
                           StringRedisTemplate redisTemplate,
                           EmailService emailService,
                           KnowledgeRepository knowledgeRepository,
                           org.springframework.jdbc.core.JdbcTemplate jdbc){
        this.userMapper=userMapper;
        this.passwordEncoder=passwordEncoder;
        this.jwtUtil=jwtUtil;
        this.redisTemplate=redisTemplate;
        this.emailService=emailService;
        this.knowledgeRepository=knowledgeRepository;
        this.jdbc=jdbc;
    }

    //增删改查
    @Override
    public void addUser(UserCreateDTO dto) {
        log.info("开始添加用户");
        User user=new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        userMapper.insert(user);

        log.info("用户添加成功，id={}",user.getId());
    }

    public UserVO getUserById(Long id){
        log.info("开始查询用户");
        User user= userMapper.selectById(id);

        if(user==null){
            log.warn("查询不到用户，id={}",id);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        log.info("查询成功，id={}",id);
        return convertToVO(user);

    }

    public PageResult<UserVO> getAllUser(Integer page, Integer size) {
        if (page == null || size == null || page < 1 || page > 1000000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        log.info("开始查询所有用户");
        int offset = (page - 1) * size;
        List<UserVO> records = userMapper.selectAll(offset, size);
        Long total = userMapper.countUsers();
        log.info("查询成功");
        return new PageResult<>(records,total,page,size);
    }

    public void updateUser(Long id,UserUpdateDTO dto){
        log.info("开始更新用户，id={}",id);

        User user=userMapper.selectById(id);
        if(user==null){
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if(dto.getUsername()!=null){
            user.setUsername(dto.getUsername());
        }
        if(dto.getEmail()!=null){
            user.setEmail(dto.getEmail());
        }
        if(dto.getPassword()!=null){
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
         userMapper.update(user);
        log.info("更新成功");
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteUser(Long id){
        log.info("开始删除用户id={}",id);

        User user=userMapper.selectById(id);
        if(user==null){
            log.warn("用户不存在");
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (knowledgeRepository.countUserBases(id) > 0) {
            throw new BusinessException(ErrorCode.USER_HAS_KNOWLEDGE_BASES);
        }

        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM interview_session WHERE user_id=?)
                    OR EXISTS(SELECT 1 FROM agent_execution WHERE user_id=?)
                    OR EXISTS(SELECT 1 FROM agent_conversation WHERE user_id=?)
                """, Boolean.class,id,id,id))) {
            throw new BusinessException(ErrorCode.USER_HAS_BUSINESS_DATA);
        }
        jdbc.update("DELETE FROM user_learning_profile WHERE user_id=?",id);
         userMapper.deleteById(id);
        redisTemplate.delete("login:user:" + id);
        log.info("删除成功id={}",id);

    }
//业务逻辑
    @Override
    public void register(UserRegisterDTO dto) {

        // 1. 根据邮箱拼 Redis key
        String key =
                "register:code:" + dto.getEmail();

        // 2. 从 Redis 查询验证码
        String redisCode =
                redisTemplate
                        .opsForValue()
                        .get(key);

        // 3. 验证码已经过期
        if (redisCode == null) {
            throw new BusinessException(
                    ErrorCode.CODE_EXPIRED
            );
        }

        // 4. 验证码错误
        if (!redisCode.equals(dto.getCode())) {
            throw new BusinessException(
                    ErrorCode.CODE_ERROR
            );
        }

        // 1. 检查用户名是否已经存在
        User oldUser = userMapper.selectByUsername(dto.getUsername());

        if (oldUser != null) {
            log.warn("用户名已存在");
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 2. 创建 User
        User user = new User();

        user.setUsername(dto.getUsername());

        // 3. BCrypt 加密密码
        String encodedPassword =
                passwordEncoder.encode(dto.getPassword());

        user.setPassword(encodedPassword);

        user.setEmail(dto.getEmail());

        // 4. 保存数据库
        userMapper.insert(user);
        log.info("注册成功，username={}",user.getUsername());

        // 8. 注册成功，删除验证码
        redisTemplate.delete(key);
}

    @Override
    public LoginVO login(UserLoginDTO loginDTO) {

        // 1. 根据用户名查询用户
        User user =
                userMapper.selectByUsername(
                        loginDTO.getUsername()
                );

        // 2. 判断用户是否存在
        if (user == null) {
            log.warn("用户名或密码错误");
            throw new BusinessException(
                    ErrorCode.USER_NOT_FOUND
            );

        }

        // 3. 验证密码
        boolean matches = passwordEncoder.matches(
                loginDTO.getPassword(),
                user.getPassword()
        );

        if (!matches) {
            throw new BusinessException(
                    ErrorCode.LOGIN_FAILED
            );
        }

        // 4. 生成 JWT
        String token =
                jwtUtil.generateToken(user.getId());

        String key="login:user:"+user.getId();

        redisTemplate.opsForValue().set(key,token,24,TimeUnit.HOURS);

        log.info("登录成功：username={}",user.getUsername());
        // 5. 返回登录信息
        return new LoginVO(
                token,
                user.getId(),
                user.getUsername()
        );
    }

    public void sendRegisterCode(String email){
        String code =
                String.valueOf(
                        ThreadLocalRandom.current()
                                .nextInt(100000, 1000000)
                );

        // 4. 发送邮件
        emailService.sendVerificationCode(
                email,
                code
        );

        // 2. Redis key
        String key =
                "register:code:" + email;

        // 3. 保存 Redis，5分钟过期
        redisTemplate
                .opsForValue()
                .set(
                        key,
                        code,
                        5,
                        TimeUnit.MINUTES
                );
    }

    public void logout() {

        Long userId =
                UserContext.getUserId();

        String key =
                "login:user:" + userId;

        redisTemplate.delete(key);
    }

    private UserVO convertToVO(User user){
        UserVO vo=new UserVO();
        vo.setEmail(user.getEmail());
        vo.setUsername(user.getUsername());
        vo.setId(user.getId());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }

}
