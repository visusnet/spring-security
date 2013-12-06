/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
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

package org.springframework.security.ldap.authentication;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.encoding.LdapShaPasswordEncoder;
import org.springframework.security.authentication.encoding.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.util.Assert;

import java.util.Arrays;


/**
 * An {@link org.springframework.security.ldap.authentication.LdapAuthenticator LdapAuthenticator} which compares the login
 * password with the value stored in the directory using a remote LDAP "compare" operation.
 *
 * <p>
 * If passwords are stored in digest form in the repository, then a suitable {@link PasswordEncoder}
 * implementation must be supplied. By default, passwords are encoded using the {@link LdapShaPasswordEncoder}.
 * Compare operations will work if salted-SHA (SSHA) passwords are used. The salt will be extracted from the userPassword
 * attribute.
 *
 * @author Luke Taylor
 */
public final class PasswordComparisonAuthenticator extends AbstractLdapAuthenticator {
        //~ Static fields/initializers =====================================================================================

        private static final Log logger = LogFactory.getLog(PasswordComparisonAuthenticator.class);

        private static final int SHA_HASH_LENGTH = 20;

        //~ Instance fields ================================================================================================

        private PasswordEncoder passwordEncoder = new LdapShaPasswordEncoder();
        private String passwordAttributeName = "userPassword";

        //~ Constructors ===================================================================================================

        public PasswordComparisonAuthenticator(BaseLdapPathContextSource contextSource) {
                super(contextSource);
        }

        //~ Methods ========================================================================================================

        public DirContextOperations authenticate(final Authentication authentication) {
                Assert.isInstanceOf(UsernamePasswordAuthenticationToken.class, authentication,
                        "Can only process UsernamePasswordAuthenticationToken objects");
                // locate the user and check the password

                DirContextOperations user = null;
                String username = authentication.getName();
                String password = (String) authentication.getCredentials();

                SpringSecurityLdapTemplate ldapTemplate = new SpringSecurityLdapTemplate(getContextSource());

                for (String userDn : getUserDns(username)) {
                        try {
                                user = ldapTemplate.retrieveEntry(userDn, getUserAttributes());
                        } catch (NameNotFoundException ignore) {
                        }
                        if (user != null) {
                                break;
                        }
                }

                if (user == null && getUserSearch() != null) {
                        user = getUserSearch().searchForUser(username);
                }

                if (user == null) {
                        throw new UsernameNotFoundException("User not found: " + username, username);
                }

                if (logger.isDebugEnabled()) {
                        logger.debug("Performing LDAP compare of password attribute '" + passwordAttributeName + "' for user '" +
                                user.getDn() + "'");
                }

                boolean useSshaSalt = passwordEncoder instanceof LdapShaPasswordEncoder;
                String encodedPassword = passwordEncoder.encodePassword(password, useSshaSalt ? extractSalt(user) : null);
                byte[] passwordBytes = Utf8.encode(encodedPassword);

                if (!ldapTemplate.compare(user.getDn().toString(), passwordAttributeName, passwordBytes)) {
                        throw new BadCredentialsException(messages.getMessage("PasswordComparisonAuthenticator.badCredentials",
                                "Bad credentials"));
                }

                return user;
        }

        private byte[] extractSalt(DirContextOperations user) {
                String userPassword = new String((byte[]) user.getObjectAttribute(passwordAttributeName));
                Assert.hasLength(userPassword, String.format("The %s attribute of the user is not set or empty.", passwordAttributeName));
                return extractSalt(userPassword);
        }

        private byte[] extractSalt(String encodedPassword) {
                // Strip off {hash} prefix. What's left is a Base64 encoded password with its salt.
                String base64EncodedPasswordWithSalt = encodedPassword.substring(encodedPassword.lastIndexOf("}") + 1);

                // Convert the Base64 encoded password with its salt to a byte array.
                byte[] passwordWithSalt = Base64.decode(base64EncodedPasswordWithSalt.getBytes());

                if (passwordWithSalt.length == SHA_HASH_LENGTH) {
                        // Uh, there is no salt! This seems to be a SHA-1 encoded password.
                        return null;
                } else if (passwordWithSalt.length < SHA_HASH_LENGTH) {
                        throw new IllegalArgumentException(
                                "The supplied password SHA/SSHA hash is invalid. Its length is less than 20 bytes.");
                }

                // The SHA password has a length of 20 bytes. Everything beyond is the salt.
                byte[] salt = Arrays.copyOfRange(passwordWithSalt, SHA_HASH_LENGTH, passwordWithSalt.length);

                return salt;
        }

        public void setPasswordAttributeName(String passwordAttribute) {
                Assert.hasLength(passwordAttribute, "passwordAttributeName must not be empty or null");
                this.passwordAttributeName = passwordAttribute;
        }

        public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
                Assert.notNull(passwordEncoder, "passwordEncoder must not be null.");
                this.passwordEncoder = passwordEncoder;
        }
}
