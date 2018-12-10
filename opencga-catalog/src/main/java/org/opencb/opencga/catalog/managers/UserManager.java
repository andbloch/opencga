/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.catalog.managers;

import org.apache.commons.lang3.StringUtils;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.audit.AuditManager;
import org.opencb.opencga.catalog.audit.AuditRecord;
import org.opencb.opencga.catalog.auth.authentication.*;
import org.opencb.opencga.catalog.auth.authorization.AuthorizationManager;
import org.opencb.opencga.catalog.db.DBAdaptorFactory;
import org.opencb.opencga.catalog.db.api.UserDBAdaptor;
import org.opencb.opencga.catalog.exceptions.*;
import org.opencb.opencga.catalog.io.CatalogIOManagerFactory;
import org.opencb.opencga.catalog.utils.ParamUtils;
import org.opencb.opencga.core.config.AuthenticationOrigin;
import org.opencb.opencga.core.config.Configuration;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.results.LdapImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class UserManager extends AbstractManager {

    private String INTERNAL_AUTHORIZATION = CatalogAuthenticationManager.INTERNAL;
    private Map<String, AuthenticationManager> authenticationManagerMap;
    private final String ADMIN_TOKEN;

    protected static final String EMAIL_PATTERN = "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
            + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
    protected static final Pattern EMAILPATTERN = Pattern.compile(EMAIL_PATTERN);
    protected static Logger logger = LoggerFactory.getLogger(UserManager.class);

    UserManager(AuthorizationManager authorizationManager, AuditManager auditManager, CatalogManager catalogManager,
                DBAdaptorFactory catalogDBAdaptorFactory, CatalogIOManagerFactory ioManagerFactory,
                Configuration configuration) throws CatalogException {
        super(authorizationManager, auditManager, catalogManager, catalogDBAdaptorFactory, ioManagerFactory, configuration);

        String secretKey = configuration.getAdmin().getSecretKey();
        long expiration = configuration.getAuthentication().getExpiration();

        authenticationManagerMap = new LinkedHashMap<>();
        if (configuration.getAuthentication().getAuthenticationOrigins() != null) {
            for (AuthenticationOrigin authenticationOrigin : configuration.getAuthentication().getAuthenticationOrigins()) {
                if (authenticationOrigin.getId() != null) {
                    switch (authenticationOrigin.getType()) {
                        case LDAP:
                            authenticationManagerMap.put(authenticationOrigin.getId(),
                                    new LDAPAuthenticationManager(authenticationOrigin, secretKey, expiration));
                            break;
                        case AzureAD:
                            authenticationManagerMap.put(authenticationOrigin.getId(),
                                    new AzureADAuthenticationManager(authenticationOrigin));
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        // Even if internal authentication is not present in the configuration file, create it
        authenticationManagerMap.putIfAbsent(INTERNAL_AUTHORIZATION,
                new CatalogAuthenticationManager(catalogDBAdaptorFactory, configuration.getEmail(), secretKey, expiration));
        AuthenticationOrigin authenticationOrigin = new AuthenticationOrigin();
        if (configuration.getAuthentication().getAuthenticationOrigins() == null) {
            configuration.getAuthentication().setAuthenticationOrigins(Arrays.asList(authenticationOrigin));
        } else {
            // Check if OPENCGA authentication is already present in catalog configuration
            boolean catalogPresent = false;
            for (AuthenticationOrigin origin : configuration.getAuthentication().getAuthenticationOrigins()) {
                if (AuthenticationOrigin.AuthenticationType.OPENCGA == origin.getType()) {
                    catalogPresent = true;
                    break;
                }
            }
            if (!catalogPresent) {
                List<AuthenticationOrigin> linkedList = new LinkedList<>();
                linkedList.addAll(configuration.getAuthentication().getAuthenticationOrigins());
                linkedList.add(authenticationOrigin);
                configuration.getAuthentication().setAuthenticationOrigins(linkedList);
            }
        }

        ADMIN_TOKEN = authenticationManagerMap.get(INTERNAL_AUTHORIZATION).createNonExpiringToken("admin");
    }

    static void checkEmail(String email) throws CatalogException {
        if (email == null || !EMAILPATTERN.matcher(email).matches()) {
            throw new CatalogException("email not valid");
        }
    }

    /**
     * Get the userId from the sessionId.
     *
     * @param sessionId SessionId
     * @return UserId owner of the sessionId. Empty string if SessionId does not match.
     * @throws CatalogException when the session id does not correspond to any user or the token has expired.
     */
    public String getUserId(String sessionId) throws CatalogException {
        return authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(sessionId);
    }

    public void changePassword(String userId, String oldPassword, String newPassword) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
//        checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(oldPassword, "oldPassword");
        ParamUtils.checkParameter(newPassword, "newPassword");
        if (oldPassword.equals(newPassword)) {
            throw new CatalogException("New password is the same as the old password.");
        }

        userDBAdaptor.checkId(userId);
        String authOrigin = getAuthenticationOriginId(userId);
        authenticationManagerMap.get(authOrigin).changePassword(userId, oldPassword, newPassword);
        userDBAdaptor.updateUserLastModified(userId);
    }

    public QueryResult<User> create(User user, @Nullable String token) throws CatalogException {
        // Check if the users can be registered publicly or just the admin.
        if (!authorizationManager.isPublicRegistration()) {
            if (!"admin".equals(authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(token))) {
                throw new CatalogException("The registration is closed to the public: Please talk to your administrator.");
            }
        }

        ParamUtils.checkObj(user, "User");
        ParamUtils.checkValidUserId(user.getId());
        ParamUtils.checkParameter(user.getName(), "name");
        checkEmail(user.getEmail());
        ParamUtils.checkObj(user.getAccount(), "account");
        user.setOrganization(ParamUtils.defaultObject(user.getOrganization(), ""));

        String password = "";
        if (StringUtils.isEmpty(user.getPassword())) {
            // The authentication origin must be different than internal
            Set<String> authOrigins = configuration.getAuthentication().getAuthenticationOrigins()
                    .stream()
                    .map(AuthenticationOrigin::getId)
                    .collect(Collectors.toSet());
            if (!authOrigins.contains(user.getAccount().getAuthOrigin())) {
                throw new CatalogException("Unknown authentication origin id '" + user.getAccount().getAuthOrigin() + "'");
            }
        } else {
            password = user.getPassword();
            user.setPassword("");

            user.getAccount().setAuthOrigin(INTERNAL_AUTHORIZATION);
        }

        checkUserExists(user.getId());
        user.setStatus(new User.UserStatus());

        if (StringUtils.isNotEmpty(user.getAccount().getType())) {
            if (!Account.FULL.equals(user.getAccount().getType()) && !Account.GUEST.equals(user.getAccount().getType())) {
                throw new CatalogException("The account type specified does not correspond with any of the valid ones. Valid account types:"
                        + Account.FULL + " and " + Account.GUEST);
            }
        } else {
            user.getAccount().setType(Account.GUEST);
        }

        if (user.getQuota() <= 0L) {
            user.setQuota(-1L);
        }

        try {
            catalogIOManagerFactory.getDefault().createUser(user.getId());
            QueryResult<User> queryResult = userDBAdaptor.insert(user, QueryOptions.empty());
            auditManager.recordCreation(AuditRecord.Resource.user, user.getId(), user.getId(), queryResult.first(), null, null);

            if (StringUtils.isNotEmpty(password)) {
                authenticationManagerMap.get(INTERNAL_AUTHORIZATION).newPassword(user.getId(), password);
            }

            return queryResult;
        } catch (CatalogIOException | CatalogDBException e) {
            if (!userDBAdaptor.exists(user.getId())) {
                logger.error("ERROR! DELETING USER! " + user.getId());
                catalogIOManagerFactory.getDefault().deleteUser(user.getId());
            }
            throw e;
        }
    }

        /**
         * Create a new user.
         *
         * @param id           User id
         * @param name         Name
         * @param email        Email
         * @param password     Encrypted Password
         * @param organization Optional organization
         * @param quota        Maximum user disk quota
         * @param accountType  User account type. Full or guest.
         * @param options      Optional options
         * @return The created user
         * @throws CatalogException If user already exists, or unable to create a new user.
         */
    public QueryResult<User> create(String id, String name, String email, String password, String organization, Long quota,
                                    String accountType, QueryOptions options) throws CatalogException {
        String token = authenticationManagerMap.get(INTERNAL_AUTHORIZATION).authenticate("admin", configuration.getAdmin().getPassword());

        User user = new User(id, name, email, password, organization, User.UserStatus.READY)
                .setAccount(new Account(accountType, "", "", ""))
                .setQuota(quota != null ? quota : 0L);

        return create(user, token);
    }

    /**
     * Register all the users belonging to a remote group. If internalGroup and study are not null, it will also associate the remote group
     * to the internalGroup defined.
     *
     * @param authOrigin Authentication origin.
     * @param remoteGroup Group name of the remote authentication origin.
     * @param internalGroup Group name in Catalog that will be associated to the remote group.
     * @param study Study where the internal group will be associated.
     * @param sync Boolean indicating whether the remote group will be synced with the internal group or not.
     * @param token JWT token. The token should belong to the root user.
     * @throws CatalogException If any of the parameters is wrong or there is any internal error.
     */
    public void importRemoteGroupOfUsers(String authOrigin, String remoteGroup, @Nullable String internalGroup, @Nullable String study,
                                         boolean sync, String token) throws CatalogException {
        if (!"admin".equals(authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(token))) {
            throw new CatalogAuthorizationException("Only the root user can perform this action");
        }

        ParamUtils.checkParameter(authOrigin, "Authentication origin");
        ParamUtils.checkParameter(remoteGroup, "Remote group");

        if (!authenticationManagerMap.containsKey(authOrigin)) {
            throw new CatalogException("Unknown authentication origin");
        }

        List<User> userList;
        if (sync) {
            // We don't create any user as they will be automatically populated during login
            userList = Collections.emptyList();
        } else {
            logger.info("Fetching users from authentication origin '{}'", authOrigin);

            // Register the users
            userList = authenticationManagerMap.get(authOrigin).getUsersFromRemoteGroup(remoteGroup);
            for (User user : userList) {
                try {
                    create(user, token);
                    logger.info("User '{}' successfully created", user.getId());
                } catch (CatalogException e) {
                    logger.warn("{}", e.getMessage());
                }
            }
        }

        if (StringUtils.isNotEmpty(internalGroup) && StringUtils.isNotEmpty(study)) {
            // Check if the group already exists
            try {
                QueryResult<Group> group = catalogManager.getStudyManager().getGroup(study, internalGroup, token);
                if (group.getNumResults() == 1) {
                    logger.error("Cannot synchronise with group {}. The group already exists and is already in use.", internalGroup);
                    return;
                }
            } catch (CatalogException e) {
                logger.warn("The group '{}' did not exist.", internalGroup);
            }

            // Create new group associating it to the remote group
            try {
                logger.info("Attempting to register group '{}' in study '{}'", internalGroup, study);
                Group.Sync groupSync = null;
                if (sync) {
                    groupSync = new Group.Sync(authOrigin, remoteGroup);
                }
                Group group = new Group(internalGroup, userList.stream().map(User::getId).collect(Collectors.toList()))
                        .setSyncedFrom(groupSync);
                catalogManager.getStudyManager().createGroup(study, group, ADMIN_TOKEN);
            } catch (CatalogException e) {
                logger.error("Could not register group '{}' in study '{}'\n{}", internalGroup, study, e.getMessage(), e);
            }
        }
    }

    /**
     * Register all the users belonging to a remote group. If internalGroup and study are not null, it will also associate the remote group
     * to the internalGroup defined.
     *
     * @param authOrigin Authentication origin.
     * @param userList List of users existing in the authentication origin.
     * @param internalGroup Group name in Catalog that will be associated to the remote group.
     * @param study Study where the internal group will be associated.
     * @param token JWT token. The token should belong to the root user.
     * @throws CatalogException If any of the parameters is wrong or there is any internal error.
     */
    public void importRemoteUsers(String authOrigin, List<String> userList, @Nullable String internalGroup, @Nullable String study,
                                         String token) throws CatalogException {
        if (!"admin".equals(authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(token))) {
            throw new CatalogAuthorizationException("Only the root user can perform this action");
        }

        ParamUtils.checkParameter(authOrigin, "Authentication origin");
        ParamUtils.checkObj(userList, "users");

        if (!authenticationManagerMap.containsKey(authOrigin)) {
            throw new CatalogException("Unknown authentication origin");
        }

        logger.info("Fetching user information from authentication origin '{}'", authOrigin);

        List<User> parsedUserList = authenticationManagerMap.get(authOrigin).getRemoteUserInformation(userList);
        for (User user : parsedUserList) {
            try {
                create(user, token);
                logger.info("User '{}' successfully created", user.getId());
            } catch (CatalogException e) {
                logger.warn("{}", e.getMessage());
            }
        }

        if (StringUtils.isNotEmpty(internalGroup) && StringUtils.isNotEmpty(study)) {
            // Check if the group already exists
            try {
                QueryResult<Group> group = catalogManager.getStudyManager().getGroup(study, internalGroup, token);
                if (group.getNumResults() == 1) {
                    // We will add those users to the existing group
                    catalogManager.getStudyManager().updateGroup(study, internalGroup,
                            new GroupParams(StringUtils.join(userList, ","), GroupParams.Action.ADD), token);
                    return;
                }
            } catch (CatalogException e) {
                logger.warn("The group '{}' did not exist.", internalGroup);
            }

            // Create new group associating it to the remote group
            try {
                logger.info("Attempting to register group '{}' in study '{}'", internalGroup, study);
                Group group = new Group(internalGroup, userList);
                catalogManager.getStudyManager().createGroup(study, group, ADMIN_TOKEN);
            } catch (CatalogException e) {
                logger.error("Could not register group '{}' in study '{}'\n{}", internalGroup, study, e.getMessage());
            }
        }
    }

    /**
     * This method can only be run by the admin user. It will import users and groups from other authentication origins such as LDAP,
     * Kerberos, etc into catalog.
     * <p>
     * @param authOrigin    Id present in the catalog configuration of the authentication origin.
     * @param accountType   Type of the account to be created for the imported users (guest, full).
     * @param params        Object map containing other parameters that are useful to import users.
     * @param sessionId     Valid admin token.
     * @return LdapImportResult Object containing a summary of the actions performed.
     * @throws CatalogException catalogException
     */
    @Deprecated
    public LdapImportResult importFromExternalAuthOrigin(String authOrigin, String accountType, ObjectMap params, String sessionId)
            throws CatalogException {
        try {
            if (!authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(sessionId).equals("admin")) {
                throw new CatalogException("Token does not belong to admin");
            }
        } catch (CatalogException e) {
            // We build an LdapImportResult that will contain the error message + the input information provided.
            logger.error(e.getMessage(), e);
            LdapImportResult retResult = new LdapImportResult();
            LdapImportResult.Input input = new LdapImportResult.Input(params.getAsStringList("users"), params.getString("group"),
                    params.getString("study-group"), authOrigin, accountType, params.getString("study"));
            retResult.setInput(input);
            retResult.setErrorMsg(e.getMessage());
            return retResult;
        }

        return importFromExternalAuthOrigin(authOrigin, accountType, params);
    }

    /**
     * Reads a user from Catalog given a user id.
     *
     * @param userId    user id of the object to read
     * @param options   Read options
     * @param sessionId sessionId
     * @return The specified object
     * @throws CatalogException CatalogException
     */
    public QueryResult<User> get(String userId, QueryOptions options, String sessionId) throws CatalogException {
        return get(userId, null, options, sessionId);
    }

    /**
     * Gets the user information.
     *
     * @param userId       User id
     * @param lastModified If lastModified matches with the one in Catalog, return an empty QueryResult.
     * @param options      QueryOptions
     * @param sessionId    SessionId of the user performing this operation.
     * @return The requested user
     * @throws CatalogException CatalogException
     */
    public QueryResult<User> get(String userId, String lastModified, QueryOptions options, String sessionId)
            throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        validateUserAndToken(userId, sessionId);
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        QueryResult<User> userQueryResult = userDBAdaptor.get(userId, options, lastModified);

        // Remove some unnecessary and prohibited parameters
        for (User user : userQueryResult.getResult()) {
            user.setPassword(null);
            if (user.getProjects() != null) {
                for (Project project : user.getProjects()) {
                    if (project.getStudies() != null) {
                        for (Study study : project.getStudies()) {
                            study.setVariableSets(null);
                        }
                    }
                }
            }
        }
        return userQueryResult;
    }

    public QueryResult<User> update(String userId, ObjectMap parameters, QueryOptions options, String token)
            throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkObj(parameters, "parameters");
        ParamUtils.checkParameter(token, "token");

        validateUserAndToken(userId, token);
        for (String s : parameters.keySet()) {
            if (!s.matches("name|email|organization|attributes")) {
                throw new CatalogDBException("Parameter '" + s + "' can't be changed");
            }
        }

        if (parameters.containsKey("email")) {
            checkEmail(parameters.getString("email"));
        }
        userDBAdaptor.updateUserLastModified(userId);
        QueryResult<User> queryResult = userDBAdaptor.update(userId, parameters);
        auditManager.recordUpdate(AuditRecord.Resource.user, userId, userId, parameters, null, null);
        return queryResult;
    }

    /**
     * Delete entries from Catalog.
     *
     * @param userIdList Comma separated list of ids corresponding to the objects to delete
     * @param options    Deleting options.
     * @param token      Token
     * @return A list with the deleted objects
     * @throws CatalogException CatalogException.
     */
    public List<QueryResult<User>> delete(String userIdList, QueryOptions options, String token) throws CatalogException {
        ParamUtils.checkParameter(userIdList, "userIdList");
        ParamUtils.checkParameter(token, "token");

        String tokenUser = getUserId(token);

        List<String> userIds = Arrays.asList(userIdList.split(","));
        List<QueryResult<User>> deletedUsers = new ArrayList<>(userIds.size());
        for (String userId : userIds) {
            if ("admin".equals(tokenUser) || userId.equals(tokenUser)) {
                QueryResult<User> deletedUser = userDBAdaptor.delete(userId, options);
                auditManager.recordDeletion(AuditRecord.Resource.user, userId, tokenUser, deletedUser.first(), null, null);
                deletedUsers.add(deletedUser);
            }
        }
        return deletedUsers;
    }

    /**
     * Delete the entries satisfying the query.
     *
     * @param query     Query of the objects to be deleted.
     * @param options   Deleting options.
     * @param sessionId sessionId.
     * @return A list with the deleted objects.
     * @throws CatalogException CatalogException
     * @throws IOException      IOException.
     */
    public List<QueryResult<User>> delete(Query query, QueryOptions options, String sessionId) throws CatalogException, IOException {
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, UserDBAdaptor.QueryParams.ID.key());
        QueryResult<User> userQueryResult = userDBAdaptor.get(query, queryOptions);
        List<String> userIds = userQueryResult.getResult().stream().map(User::getId).collect(Collectors.toList());
        String userIdStr = StringUtils.join(userIds, ",");
        return delete(userIdStr, options, sessionId);
    }

    public List<QueryResult<User>> restore(String ids, QueryOptions options, String sessionId) throws CatalogException {
        throw new UnsupportedOperationException();
    }

    public QueryResult resetPassword(String userId, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        validateUserAndToken(userId, sessionId);

        String authOrigin = getAuthenticationOriginId(userId);
        return authenticationManagerMap.get(authOrigin).resetPassword(userId);
    }

    public String login(String username, String password) throws CatalogException {
        ParamUtils.checkParameter(username, "userId");
        ParamUtils.checkParameter(password, "password");

        String authId = null;
        String token = null;

        // We attempt to login the user with the different authentication managers
        for (Map.Entry<String, AuthenticationManager> entry : authenticationManagerMap.entrySet()) {
            AuthenticationManager authenticationManager = entry.getValue();
            try {
                token = authenticationManager.authenticate(username, password);
                authId = entry.getKey();
                break;
            } catch (CatalogAuthenticationException e) {
                logger.warn("Attempted authentication failed with {} for user '{}'\n{}", entry.getKey(), username, e.getMessage(), e);
            }
        }

        if (token == null) {
            // TODO: We should raise better exceptions. It could fail for other reasons.
            auditManager.recordLogin(username, false);
            throw CatalogAuthenticationException.incorrectUserOrPassword();
        }

        String userId = authenticationManagerMap.get(authId).getUserId(token);
        if (!INTERNAL_AUTHORIZATION.equals(authId)) {
            // External authorization
            try {
                // If the user is not registered, an exception will be raised
                userDBAdaptor.checkId(userId);
            } catch (CatalogDBException e) {
                // The user does not exist so we register it
                User user = authenticationManagerMap.get(authId).getRemoteUserInformation(Collections.singletonList(userId)).get(0);
                create(user, ADMIN_TOKEN);
            }

            try {
                List<String> remoteGroups = authenticationManagerMap.get(authId).getRemoteGroups(token);

                // Resync synced groups of user in OpenCGA
                studyDBAdaptor.resyncUserWithSyncedGroups(userId, remoteGroups, authId);
            } catch (CatalogException e) {
                logger.error("Could not update synced groups for user '" + userId + "'\n" + e.getMessage(), e);
            }
        }

        auditManager.recordLogin(userId, true);
        return token;
    }

    /**
     * Create a new token if the token provided corresponds to the user and it is not expired yet.
     *
     * @param userId user id to whom the token belongs to.
     * @param token  active token.
     * @return a new token with the default expiration updated.
     * @throws CatalogException if the token does not correspond to the user or the token is expired.
     */
    public String refreshToken(String userId, String token) throws CatalogException {
        if (!userId.equals(authenticationManagerMap.get(INTERNAL_AUTHORIZATION).getUserId(token))) {
            throw new CatalogException("Cannot refresh token. The token received does not correspond to " + userId);
        }
        return authenticationManagerMap.get(INTERNAL_AUTHORIZATION).createToken(userId);
    }

    /**
     * This method will be only callable by the system. It generates a new session id for the user.
     *
     * @param userId           user id for which a session will be generated.
     * @param adminCredentials Password or active session of the OpenCGA admin.
     * @return an objectMap containing the new sessionId
     * @throws CatalogException if the password is not correct or the userId does not exist.
     */
    public String getSystemTokenForUser(String userId, String adminCredentials) throws CatalogException {
        validateUserAndToken("admin", adminCredentials);
        return authenticationManagerMap.get(INTERNAL_AUTHORIZATION).createNonExpiringToken(userId);
    }

    /**
     * Add a new filter to the user account.
     * <p>
     * @param userId       user id to whom the filter will be associated.
     * @param name         Filter name.
     * @param description  Filter description.
     * @param bioformat    Bioformat where the filter should be applied.
     * @param query        Query object.
     * @param queryOptions Query options object.
     * @param sessionId    session id of the user asking to store the filter.
     * @return the created filter.
     * @throws CatalogException if there already exists a filter with that same name for the user or if the user corresponding to the
     *                          session id is not the same as the provided user id.
     */
    public QueryResult<User.Filter> addFilter(String userId, String name, String description, File.Bioformat bioformat, Query query,
                                              QueryOptions queryOptions, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");
        ParamUtils.checkObj(bioformat, "bioformat");
        ParamUtils.checkObj(query, "Query");
        ParamUtils.checkObj(queryOptions, "QueryOptions");
        if (description == null) {
            description = "";
        }

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to store filters for user " + userId);
        }

        Query queryExists = new Query()
                .append(UserDBAdaptor.QueryParams.ID.key(), userId)
                .append(UserDBAdaptor.QueryParams.CONFIGS_FILTERS_NAME.key(), name);
        if (userDBAdaptor.count(queryExists).first() > 0) {
            throw new CatalogException("There already exists a filter called " + name + " for user " + userId);
        }

        User.Filter filter = new User.Filter(name, description, bioformat, query, queryOptions);
        return userDBAdaptor.addFilter(userId, filter);
    }

    /**
     * Update the filter information.
     * <p>
     * @param userId    user id to whom the filter should be updated.
     * @param name      Filter name.
     * @param params    Map containing the parameters to be updated.
     * @param sessionId session id of the user asking to update the filter.
     * @return the updated filter.
     * @throws CatalogException if the filter could not be updated because the filter name is not correct or if the user corresponding to
     *                          the session id is not the same as the provided user id.
     */
    public QueryResult<User.Filter> updateFilter(String userId, String name, ObjectMap params, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to update filters for user " + userId);
        }

        Query queryExists = new Query()
                .append(UserDBAdaptor.QueryParams.ID.key(), userId)
                .append(UserDBAdaptor.QueryParams.CONFIGS_FILTERS_NAME.key(), name);
        if (userDBAdaptor.count(queryExists).first() == 0) {
            throw new CatalogException("There is no filter called " + name + " for user " + userId);
        }

        QueryResult<Long> queryResult = userDBAdaptor.updateFilter(userId, name, params);
        User.Filter filter = getFilter(userId, name);
        if (filter == null) {
            throw new CatalogException("Internal error: The filter " + name + " could not be found.");
        }

        return new QueryResult<>("Update filter", queryResult.getDbTime(), 1, 1, queryResult.getWarningMsg(), queryResult.getErrorMsg(),
                Arrays.asList(filter));
    }

    /**
     * Delete the filter.
     * <p>
     * @param userId    user id to whom the filter should be deleted.
     * @param name      filter name to be deleted.
     * @param sessionId session id of the user asking to delete the filter.
     * @return the deleted filter.
     * @throws CatalogException when the filter cannot be removed or the name is not correct or if the user corresponding to the
     *                          session id is not the same as the provided user id.
     */
    public QueryResult<User.Filter> deleteFilter(String userId, String name, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to delete filters for user " + userId);
        }

        User.Filter filter = getFilter(userId, name);
        if (filter == null) {
            throw new CatalogException("There is no filter called " + name + " for user " + userId);
        }

        QueryResult<Long> queryResult = userDBAdaptor.deleteFilter(userId, name);
        return new QueryResult<>("Delete filter", queryResult.getDbTime(), 1, 1, queryResult.getWarningMsg(), queryResult.getErrorMsg(),
                Arrays.asList(filter));
    }

    /**
     * Retrieves a filter.
     * <p>
     * @param userId    user id having the filter stored.
     * @param name      Filter name to be fetched.
     * @param sessionId session id of the user fetching the filter.
     * @return the filter.
     * @throws CatalogException if the user corresponding to the session id is not the same as the provided user id.
     */
    public QueryResult<User.Filter> getFilter(String userId, String name, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to get filters from user " + userId);
        }

        User.Filter filter = getFilter(userId, name);
        if (filter == null) {
            return new QueryResult<>("Get filter", 0, 0, 0, "", "Filter not found", Arrays.asList());
        } else {
            return new QueryResult<>("Get filter", 0, 1, 1, "", "", Arrays.asList(filter));
        }
    }

    /**
     * Retrieves all the user filters.
     *
     * @param userId    user id having the filters.
     * @param sessionId session id of the user fetching the filters.
     * @return the filters.
     * @throws CatalogException if the user corresponding to the session id is not the same as the provided user id.
     */
    public QueryResult<User.Filter> getAllFilters(String userId, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to get filters from user " + userId);
        }

        Query query = new Query()
                .append(UserDBAdaptor.QueryParams.ID.key(), userId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, UserDBAdaptor.QueryParams.CONFIGS.key());
        QueryResult<User> userQueryResult = userDBAdaptor.get(query, queryOptions);

        if (userQueryResult.getNumResults() != 1) {
            throw new CatalogException("Internal error: User " + userId + " not found.");
        }

        List<User.Filter> filters = userQueryResult.first().getConfigs().getFilters();

        return new QueryResult<>("Get filters", 0, filters.size(), filters.size(), "", "", filters);
    }

    /**
     * Creates or updates a configuration.
     * <p>
     * @param userId    user id to whom the config will be associated.
     * @param name      Name of the configuration (normally, name of the application).
     * @param config    Configuration to be stored.
     * @param sessionId session id of the user asking to store the config.
     * @return the set configuration.
     * @throws CatalogException if the user corresponding to the session id is not the same as the provided user id.
     */
    public QueryResult setConfig(String userId, String name, ObjectMap config, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");
        ParamUtils.checkObj(config, "ObjectMap");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to set configuration for user " + userId);
        }

        return userDBAdaptor.setConfig(userId, name, config);
    }

    /**
     * Deletes a configuration.
     * <p>
     * @param userId    user id to whom the configuration should be deleted.
     * @param name      Name of the configuration to be deleted (normally, name of the application).
     * @param sessionId session id of the user asking to delete the configuration.
     * @return the deleted configuration.
     * @throws CatalogException if the user corresponding to the session id is not the same as the provided user id or the configuration
     *                          did not exist.
     */
    public QueryResult deleteConfig(String userId, String name, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to delete the configuration of user " + userId);
        }

        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE, UserDBAdaptor.QueryParams.CONFIGS.key());
        QueryResult<User> userQueryResult = userDBAdaptor.get(userId, options, "");
        if (userQueryResult.getNumResults() == 0) {
            throw new CatalogException("Internal error: Could not get user " + userId);
        }

        User.UserConfiguration configs = userQueryResult.first().getConfigs();
        if (configs == null) {
            throw new CatalogException("Internal error: Configuration object is null.");
        }

        if (configs.get(name) == null) {
            throw new CatalogException("Error: Cannot delete configuration with name " + name + ". Configuration name not found.");
        }

        QueryResult<Long> queryResult = userDBAdaptor.deleteConfig(userId, name);
        return new QueryResult("Delete configuration", queryResult.getDbTime(), 1, 1, "", "", Arrays.asList(configs.get(name)));
    }

    /**
     * Retrieves a configuration.
     * <p>
     * @param userId    user id having the configuration stored.
     * @param name      Name of the configuration to be fetched (normally, name of the application).
     * @param sessionId session id of the user attempting to fetch the configuration.
     * @return the configuration.
     * @throws CatalogException if the user corresponding to the session id is not the same as the provided user id or the configuration
     *                          does not exist.
     */
    public QueryResult getConfig(String userId, String name, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(userId, "userId");
        ParamUtils.checkParameter(sessionId, "sessionId");
        ParamUtils.checkParameter(name, "name");

        String userIdAux = getUserId(sessionId);
        userDBAdaptor.checkId(userId);
        if (!userId.equals(userIdAux)) {
            throw new CatalogException("User " + userIdAux + " is not authorised to fetch the configuration of user " + userId);
        }

        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE, UserDBAdaptor.QueryParams.CONFIGS.key());
        QueryResult<User> userQueryResult = userDBAdaptor.get(userId, options, "");
        if (userQueryResult.getNumResults() == 0) {
            throw new CatalogException("Internal error: Could not get user " + userId);
        }

        User.UserConfiguration configs = userQueryResult.first().getConfigs();
        if (configs == null) {
            throw new CatalogException("Internal error: Configuration object is null.");
        }

        if (configs.get(name) == null) {
            throw new CatalogException("Error: Cannot fetch configuration with name " + name + ". Configuration name not found.");
        }

        return new QueryResult("Get configuration", userQueryResult.getDbTime(), 1, 1, userQueryResult.getWarningMsg(),
                userQueryResult.getErrorMsg(), Arrays.asList(configs.get(name)));
    }


    private User.Filter getFilter(String userId, String name) throws CatalogException {
        Query query = new Query()
                .append(UserDBAdaptor.QueryParams.ID.key(), userId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, UserDBAdaptor.QueryParams.CONFIGS.key());
        QueryResult<User> userQueryResult = userDBAdaptor.get(query, queryOptions);

        if (userQueryResult.getNumResults() != 1) {
            throw new CatalogException("Internal error: User " + userId + " not found.");
        }

        for (User.Filter filter : userQueryResult.first().getConfigs().getFilters()) {
            if (name.equals(filter.getName())) {
                return filter;
            }
        }

        return null;
    }

    private void validateUserAndToken(String userId, String jwtToken) throws CatalogException {
        Boolean validToken = null;
        for (AuthenticationManager authenticationManager : authenticationManagerMap.values()) {
            try {
                if (!userId.equals(authenticationManager.getUserId(jwtToken))) {
                    validToken = false;
                } else {
                    validToken = true;
                }
            } catch (CatalogException e) {
                // The authentication manager might have failed because that token was generated with a different auth manager
                continue;
            }

            if (validToken != null) {
                if (validToken) {
                    return;
                } else {
                    throw new CatalogException("Invalid sessionId for user: " + userId);
                }
            }
        }
    }

    private void checkUserExists(String userId) throws CatalogException {
        if (userId.toLowerCase().equals("admin")) {
            throw new CatalogException("Permission denied: It is not allowed the creation of another admin user.");
        } else if (userId.toLowerCase().equals(ANONYMOUS) || userId.toLowerCase().equals("daemon")) {
            throw new CatalogException("Permission denied: Cannot create users with special treatments in catalog.");
        }

        if (userDBAdaptor.exists(userId)) {
            throw new CatalogException("The user already exists in our database.");
        }
    }

    private String getAuthenticationOriginId(String userId) throws CatalogException {
        QueryResult<User> user = userDBAdaptor.get(userId, new QueryOptions(), "");
        if (user == null || user.getNumResults() == 0) {
            throw new CatalogException(userId + " user not found");
        }
        return user.first().getAccount().getAuthOrigin();
    }

    private AuthenticationOrigin getAuthenticationOrigin(String authOrigin) {
        if (configuration.getAuthentication().getAuthenticationOrigins() != null) {
            for (AuthenticationOrigin authenticationOrigin : configuration.getAuthentication().getAuthenticationOrigins()) {
                if (authOrigin.equals(authenticationOrigin.getId())) {
                    return authenticationOrigin;
                }
            }
        }
        return null;
    }

    private LdapImportResult importFromExternalAuthOrigin(String authOrigin, String accountType, ObjectMap params) throws CatalogException {
        LdapImportResult retResult = new LdapImportResult();
        LdapImportResult.Input input = new LdapImportResult.Input(params.getAsStringList("users"), params.getString("group"),
                params.getString("study-group"), authOrigin, accountType, params.getString("study"));
        retResult.setInput(input);

        if (INTERNAL_AUTHORIZATION.equals(authOrigin)) {
            retResult.setErrorMsg("Cannot import users from catalog. Authentication origin should be external.");
            return retResult;
        }

        // Obtain the authentication origin parameters
        AuthenticationOrigin authenticationOrigin = getAuthenticationOrigin(authOrigin);
        if (authenticationOrigin == null) {
            retResult.setErrorMsg("The authentication origin id " + authOrigin + " does not correspond with any id in our database.");
            return retResult;
        }

        // Check account type
        if (accountType != null) {
            if (!Account.FULL.equalsIgnoreCase(accountType) && !Account.GUEST.equalsIgnoreCase(accountType)) {
                retResult.setErrorMsg("The account type specified does not correspond with any of the valid ones. Valid account types:"
                        + Account.FULL + " and " + Account.GUEST);
                return retResult;
            }
        }

        String base = ((String) authenticationOrigin.getOptions().get(AuthenticationOrigin.GROUPS_SEARCH));
        Set<String> usersFromLDAP = new HashSet<>();
        usersFromLDAP.addAll(retResult.getInput().getUsers());
        try {
            usersFromLDAP.addAll(LDAPUtils.getUsersFromLDAPGroup(authenticationOrigin.getHost(), retResult.getInput().getGroup(), base));
        } catch (NamingException e) {
            logger.error(e.getMessage(), e);
            retResult.setErrorMsg(e.getMessage());
            return retResult;
        }

        LdapImportResult.SummaryResult summaryResult = new LdapImportResult.SummaryResult();
        Set<String> userSet = new HashSet<>();
        if (usersFromLDAP.size() > 0) {

            base = ((String) authenticationOrigin.getOptions().get(AuthenticationOrigin.USERS_SEARCH));
            List<Attributes> userAttrList;
            try {
                List<String> userList = new ArrayList<>(usersFromLDAP.size());
                userList.addAll(usersFromLDAP);
                userAttrList = LDAPUtils.getUserInfoFromLDAP(authenticationOrigin.getHost(), userList, base);
            } catch (NamingException e) {
                logger.error(e.getMessage(), e);
                retResult.setErrorMsg(e.getMessage());
                return retResult;
            }

            if (userAttrList.isEmpty()) {
                retResult.setWarningMsg("No users were found. Nothing to do.");
                return retResult;
            }

            String type;
            if (Account.GUEST.equalsIgnoreCase(accountType)) {
                type = Account.GUEST;
            } else {
                type = Account.FULL;
            }

            summaryResult.setTotal(usersFromLDAP.size());

            // Register users in catalog
            for (Attributes attrs : userAttrList) {
                String displayname;
                String mail;
                String uid;
                String rdn;
                try {
                    displayname = LDAPUtils.getFullName(attrs);
                    mail = LDAPUtils.getMail(attrs);
                    uid = LDAPUtils.getUID(attrs);
                    rdn = LDAPUtils.getRDN(attrs);
                } catch (NamingException e) {
                    logger.error(e.getMessage(), e);
                    retResult.setErrorMsg(e.getMessage());
                    return retResult;
                }

                // Check if the user already exists in catalog
                if (userDBAdaptor.exists(uid)) {
                    summaryResult.getExistingUsers().add(uid);
                    userSet.add(uid);
                    continue;
                }

                logger.debug("Registering {} in Catalog", uid);

                // Create the user in catalog
                Account account = new Account().setType(type).setAuthOrigin(authOrigin);

                // TODO: Parse expiration date
//            if (params.get("expirationDate") != null) {
//                account.setExpirationDate(...);
//            }

                Map<String, Object> attributes = new HashMap<>();
                attributes.put("LDAP_RDN", rdn);
                User user = new User(uid, displayname, mail, "", base, account, User.UserStatus.READY, "", -1, -1, new ArrayList<>(),
                        new ArrayList<>(), new HashMap<>(), attributes);


                userDBAdaptor.insert(user, QueryOptions.empty());

                summaryResult.getNewUsers().add(uid);
                userSet.add(uid);
            }

            // Check users not found in LDAP
            for (String uid : retResult.getInput().getUsers()) {
                if (!userSet.contains(uid)) {
                    summaryResult.getNonExistingUsers().add(uid);
                }
            }
        }

        LdapImportResult.Result result = new LdapImportResult.Result();
        retResult.setResult(result);
        result.setUserSummary(summaryResult);

        if (StringUtils.isEmpty(retResult.getInput().getStudy()) || StringUtils.isEmpty(retResult.getInput().getStudyGroup())) {
            return retResult;
        }
        long studyId = catalogManager.getStudyManager().getId("admin", retResult.getInput().getStudy());

        if (studyId <= 0) {
            retResult.setErrorMsg("Study not " + retResult.getInput().getStudy() + " found.");
            return retResult;
        }

        try {
            catalogManager.getStudyManager().createGroup(Long.toString(studyId), new Group(retResult.getInput().getStudyGroup(),
                    userSet.stream().collect(Collectors.toList())), ADMIN_TOKEN);
        } catch (CatalogException e) {
            if (e.getMessage().contains("users already belong to")) {
                // Cannot create a group with those users because they already belong to other group
                retResult.setErrorMsg(e.getMessage());
                return retResult;
            }
            try {
                GroupParams groupParams = new GroupParams(StringUtils.join(userSet, ","), GroupParams.Action.ADD);
                catalogManager.getStudyManager().updateGroup(Long.toString(studyId), retResult.getInput().getStudyGroup(), groupParams,
                        ADMIN_TOKEN);
            } catch (CatalogException e1) {
                retResult.setErrorMsg(e1.getMessage());
                return retResult;
            }
        }

        try {
            QueryResult<Group> group = catalogManager.getStudyManager().getGroup(Long.toString(studyId),
                    retResult.getInput().getStudyGroup(), ADMIN_TOKEN);

            retResult.getResult().setUsersInGroup(group.first().getUserIds());
        } catch (CatalogException e) {
            retResult.setErrorMsg(e.getMessage());
        }
        return retResult;
    }

//    private List<String> fetchGroupsFromLdapUser(User user, AuthenticationOrigin authenticationOrigin) throws NamingException {
//        List<String> groups = new ArrayList<>();
//        if (user == null) {
//            return groups;
//        }
//        String userRdn = (String) user.getAttributes().get("LDAP_RDN");
//        String base = ((String) authenticationOrigin.getOptions().get(AuthenticationOrigin.USERS_SEARCH));
//        return LDAPUtils.getGroupsFromLdapUser(authenticationOrigin.getHost(), userRdn, base);
//    }

}
