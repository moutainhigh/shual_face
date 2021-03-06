package com.fzy.admin.fp.auth.app;

import cn.hutool.crypto.digest.BCrypt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fzy.admin.fp.auth.domain.Company;
import com.fzy.admin.fp.auth.domain.Role;
import com.fzy.admin.fp.auth.domain.SiteInfo;
import com.fzy.admin.fp.auth.domain.User;
import com.fzy.admin.fp.auth.repository.CompanyRepository;
import com.fzy.admin.fp.auth.repository.SiteInfoRepository;
import com.fzy.admin.fp.auth.service.CompanyService;
import com.fzy.admin.fp.auth.service.UserRoleService;
import com.fzy.admin.fp.auth.service.UserService;
import com.fzy.admin.fp.common.annotation.TokenInfo;
import com.fzy.admin.fp.common.annotation.UserId;
import com.fzy.admin.fp.common.constant.CommonConstant;
import com.fzy.admin.fp.common.spring.base.BaseContent;
import com.fzy.admin.fp.common.web.ParamUtil;
import com.fzy.admin.fp.common.web.Resp;
import com.fzy.admin.fp.auth.AuthConstant;
import com.fzy.admin.fp.auth.domain.Company;
import com.fzy.admin.fp.auth.domain.Role;
import com.fzy.admin.fp.auth.domain.SiteInfo;
import com.fzy.admin.fp.auth.domain.User;
import com.fzy.admin.fp.auth.repository.CompanyRepository;
import com.fzy.admin.fp.auth.repository.SiteInfoRepository;
import com.fzy.admin.fp.auth.service.CompanyService;
import com.fzy.admin.fp.auth.service.UserRoleService;
import com.fzy.admin.fp.auth.service.UserService;
import com.fzy.admin.fp.common.annotation.TokenInfo;
import com.fzy.admin.fp.common.annotation.UserId;
import com.fzy.admin.fp.common.constant.CommonConstant;
import com.fzy.admin.fp.common.spring.base.BaseContent;
import com.fzy.admin.fp.common.web.ParamUtil;
import com.fzy.admin.fp.common.web.Resp;
import com.fzy.admin.fp.sdk.merchant.feign.MerchantBusinessService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @ Author ：drj.
 * @ Date  ：Created in 14:37 2019/5/8
 * @ Description: 一级代理商或二级代理商个人中心接口
 **/
@Slf4j
@RestController
@RequestMapping("/auth/admin/app")
public class AppPersonController extends BaseContent {

    @Resource
    private UserService userService;

    @Resource
    private CompanyRepository companyRepository;
    @Resource
    private CompanyService companyService;
    @Resource
    private UserRoleService userRoleService;
    @Resource
    private SiteInfoRepository siteInfoRepository;

    @Value("${secret.tokenExpiration}")
    private int tokenExpiration;

    @Resource
    private MerchantBusinessService merchantBusinessService;

    /*
     * @author drj
     * @date 2019-05-08 17:31
     * @Description :登录接口
     */
    @PostMapping("/login")
    public Resp login(String username, String password) {
        User user = userService.findByUsername(username, (String) request.getAttribute(CommonConstant.SERVICE_PROVIDERID));
        if (ParamUtil.isBlank(user)) {
            return new Resp().error(Resp.Status.PARAM_ERROR, "用户名不存在");
        }
        if (user.getStatus().equals(User.Status.DISABLE.getCode())) {
            return new Resp().error(Resp.Status.PARAM_ERROR, "用户已被注销");
        }
        if (!BCrypt.checkpw(password, user.getPassword())) {
            return new Resp().error(Resp.Status.PARAM_ERROR, "密码错误");
        }
        final List<Role> roles = userRoleService.findUserRoleByUserId(user.getId());
        Integer level = Role.LEVEL.COMMON.getCode();
        for (Role role : roles) {
            level = role.getLevel();
            if (Role.LEVEL.TOP.getCode().equals(role.getLevel())) {
                //如果是高级角色
                break;
            }
        }
        //查询用户对应的公司
        Company company = companyRepository.findOne(user.getCompanyId());
        String key = user.getUsername();
        String token = JWT.create().withIssuer(AuthConstant.AUTH_NAME).withSubject(user.getId())
                .withClaim("companyId", company.getId())
                .withClaim("level", level)
                .withClaim(CommonConstant.SERVICE_PROVIDERID, user.getServiceProviderId())
                .withExpiresAt(new Date(new Date().getTime() + tokenExpiration * 60 * 1000))
                .sign(Algorithm.HMAC512(key));
        Map<String, Object> map = new HashMap<>();
        map.put("token", token);
        if (company.getType().equals(Company.Type.OPERATOR.getCode())) {
            map.put("userType", 2);
        }
        if (company.getType().equals(Company.Type.DISTRIBUTUTORS.getCode())) {
            map.put("userType", 3);
        }
        return Resp.success(map, "登录成功");
    }

    /**
     * 获取用户所在域名
     *
     * @param companyId
     * @return
     */
    @GetMapping("/user/domain")
    public Resp<String> domain(@RequestAttribute(CommonConstant.SERVICE_PROVIDERID) String companyId) {
        if (companyId == null) {
            return new Resp<>().error(Resp.Status.PARAM_ERROR, "token错误");
        }
        SiteInfo siteInfo = siteInfoRepository.findByServiceProviderId(companyId);
        if (siteInfo != null && !StringUtils.isEmpty(siteInfo.getDomainName())) {
            return Resp.success("http://" + siteInfo.getDomainName(), "");
        } else {
            return new Resp<>().error(Resp.Status.PARAM_ERROR, "当前用户所在服务商未配置域名");
        }

    }

    /*
     * @author drj
     * @date 2019-05-08 17:31
     * @Description :个人中心查询详情
     */
    @GetMapping("/detail")
    public Resp detail(@UserId String userId) {
        User user = userService.findOne(userId);
        if (ParamUtil.isBlank(user)) {
            return new Resp().error(Resp.Status.PARAM_ERROR, "参数错误");
        }
        Company company = companyRepository.findOne(user.getCompanyId());
        user.setCompanyName(company.getName());
        return Resp.success(user);
    }


    /*
     * @author drj
     * @date 2019-05-08 17:31
     * @Description:查询一级代理商或者二级代理商的营业概况
     */
    @GetMapping("/business_info")
    public Resp businessInfo(@TokenInfo(property = "companyId") String companyId) {
        Map<String, Integer> map = new HashMap<>();
        Company company = companyRepository.findOne(companyId);
        if (ParamUtil.isBlank(company)) {
            return new Resp().error(Resp.Status.PARAM_ERROR, "参数错误");
        }
        //查看底下的商户数量
        Integer merchantAmount = merchantBusinessService.findByCompanyId(companyId);
        map.put("merchantAmount", merchantAmount);
        //若当前登录用户为一级代理商
        if (Company.Type.OPERATOR.getCode().equals(company.getType())) {
            Integer distributeAmount = companyRepository.findByParentIdAndDelFlag(companyId, CommonConstant.NORMAL_FLAG).size();
            map.put("distributeAmount", distributeAmount);
        }
        return Resp.success(map);
    }

}
