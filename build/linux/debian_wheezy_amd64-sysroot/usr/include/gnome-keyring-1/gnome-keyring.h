/* -*- Mode: C; indent-tabs-mode: t; c-basic-offset: 8; tab-width: 8 -*- */
/* gnome-keyring.h - library for talking to the keyring daemon.

   Copyright (C) 2003 Red Hat, Inc

   The Gnome Keyring Library is free software; you can redistribute it and/or
   modify it under the terms of the GNU Library General Public License as
   published by the Free Software Foundation; either version 2 of the
   License, or (at your option) any later version.

   The Gnome Keyring Library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Library General Public License for more details.

   You should have received a copy of the GNU Library General Public
   License along with the Gnome Library; see the file COPYING.LIB.  If not,
   write to the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
   Boston, MA 02111-1307, USA.

   Author: Alexander Larsson <alexl@redhat.com>
*/

#ifndef GNOME_KEYRING_H
#define GNOME_KEYRING_H

#include <glib.h>
#include <glib-object.h>
#include <time.h>

#include "gnome-keyring-result.h"

G_BEGIN_DECLS

#define GNOME_KEYRING_SESSION   "session"
#define GNOME_KEYRING_DEFAULT   NULL

typedef enum {

	/* The item types */
	GNOME_KEYRING_ITEM_GENERIC_SECRET = 0,
	GNOME_KEYRING_ITEM_NETWORK_PASSWORD,
	GNOME_KEYRING_ITEM_NOTE,
	GNOME_KEYRING_ITEM_CHAINED_KEYRING_PASSWORD,
	GNOME_KEYRING_ITEM_ENCRYPTION_KEY_PASSWORD,

	GNOME_KEYRING_ITEM_PK_STORAGE = 0x100,

	/* Not used, remains here only for compatibility */
	GNOME_KEYRING_ITEM_LAST_TYPE,

} GnomeKeyringItemType;

#define	GNOME_KEYRING_ITEM_TYPE_MASK 0x0000ffff
#define GNOME_KEYRING_ITEM_NO_TYPE GNOME_KEYRING_ITEM_TYPE_MASK
#define	GNOME_KEYRING_ITEM_APPLICATION_SECRET 0x01000000

typedef enum {
	GNOME_KEYRING_ATTRIBUTE_TYPE_STRING,
	GNOME_KEYRING_ATTRIBUTE_TYPE_UINT32
} GnomeKeyringAttributeType;

typedef GArray GnomeKeyringAttributeList;

typedef enum {
	GNOME_KEYRING_ITEM_INFO_BASICS = 0,
	GNOME_KEYRING_ITEM_INFO_SECRET = 1<<0
} GnomeKeyringItemInfoFlags;

/* Add flags here as they are added above */
#define GNOME_KEYRING_ITEM_INFO_ALL (GNOME_KEYRING_ITEM_INFO_BASICS | GNOME_KEYRING_ITEM_INFO_SECRET)

typedef struct GnomeKeyringInfo GnomeKeyringInfo;
typedef struct GnomeKeyringItemInfo GnomeKeyringItemInfo;

typedef struct {
	char *name;
	GnomeKeyringAttributeType type;
	union {
		char *string;
		guint32 integer;
	} value;
} GnomeKeyringAttribute;

typedef struct {
	char *keyring;
	guint item_id;
	GnomeKeyringAttributeList *attributes;
	char *secret;
} GnomeKeyringFound;

void gnome_keyring_string_list_free (GList *strings);

typedef void (*GnomeKeyringOperationDoneCallback)           (GnomeKeyringResult result,
                                                             gpointer           data);
typedef void (*GnomeKeyringOperationGetStringCallback)      (GnomeKeyringResult result,
                                                             const char        *string,
                                                             gpointer           data);
typedef void (*GnomeKeyringOperationGetIntCallback)         (GnomeKeyringResult result,
                                                             guint32            val,
                                                             gpointer           data);
typedef void (*GnomeKeyringOperationGetListCallback)        (GnomeKeyringResult result,
                                                             GList             *list,
                                                             gpointer           data);
typedef void (*GnomeKeyringOperationGetKeyringInfoCallback) (GnomeKeyringResult result,
                                                             GnomeKeyringInfo  *info,
                                                             gpointer           data);
typedef void (*GnomeKeyringOperationGetItemInfoCallback)    (GnomeKeyringResult   result,
                                                             GnomeKeyringItemInfo*info,
                                                             gpointer             data);
typedef void (*GnomeKeyringOperationGetAttributesCallback)  (GnomeKeyringResult   result,
                                                             GnomeKeyringAttributeList *attributes,
                                                             gpointer             data);

GType                      gnome_keyring_attribute_get_type           (void) G_GNUC_CONST;
const gchar*               gnome_keyring_attribute_get_string         (GnomeKeyringAttribute *attribute);
guint32                    gnome_keyring_attribute_get_uint32         (GnomeKeyringAttribute *attribute);

#define GNOME_KEYRING_TYPE_ATTRIBUTE (gnome_keyring_attribute_get_type ())

#define gnome_keyring_attribute_list_index(a, i) g_array_index ((a), GnomeKeyringAttribute, (i))
void                       gnome_keyring_attribute_list_append_string (GnomeKeyringAttributeList *attributes,
                                                                       const char                *name,
                                                                       const char                *value);
void                       gnome_keyring_attribute_list_append_uint32 (GnomeKeyringAttributeList *attributes,
                                                                       const char                *name,
                                                                       guint32                    value);
GnomeKeyringAttributeList *gnome_keyring_attribute_list_new           (void);
void                       gnome_keyring_attribute_list_free          (GnomeKeyringAttributeList *attributes);
GnomeKeyringAttributeList *gnome_keyring_attribute_list_copy          (GnomeKeyringAttributeList *attributes);
GType                      gnome_keyring_attribute_list_get_type      (void) G_GNUC_CONST;
GList                     *gnome_keyring_attribute_list_to_glist      (GnomeKeyringAttributeList *attributes);

#define GNOME_KEYRING_TYPE_ATTRIBUTE_LIST (gnome_keyring_attribute_list_get_type ())

const gchar*               gnome_keyring_result_to_message            (GnomeKeyringResult res);

gboolean gnome_keyring_is_available (void);

void gnome_keyring_found_free               (GnomeKeyringFound *found);
void gnome_keyring_found_list_free          (GList *found_list);
GnomeKeyringFound* gnome_keyring_found_copy (GnomeKeyringFound *found);
GType gnome_keyring_found_get_type          (void) G_GNUC_CONST;

#define GNOME_KEYRING_TYPE_FOUND (gnome_keyring_found_get_type ())

void gnome_keyring_cancel_request (gpointer request);

gpointer           gnome_keyring_set_default_keyring      (const char                              *keyring,
                                                           GnomeKeyringOperationDoneCallback        callback,
                                                           gpointer                                 data,
                                                           GDestroyNotify                           destroy_data);
GnomeKeyringResult gnome_keyring_set_default_keyring_sync (const char                              *keyring);
gpointer           gnome_keyring_get_default_keyring      (GnomeKeyringOperationGetStringCallback   callback,
                                                           gpointer                                 data,
                                                           GDestroyNotify                           destroy_data);
GnomeKeyringResult gnome_keyring_get_default_keyring_sync (char                                   **keyring);
gpointer           gnome_keyring_list_keyring_names       (GnomeKeyringOperationGetListCallback     callback,
                                                           gpointer                                 data,
                                                           GDestroyNotify                           destroy_data);
GnomeKeyringResult gnome_keyring_list_keyring_names_sync  (GList                                  **keyrings);
gpointer           gnome_keyring_lock_all                 (GnomeKeyringOperationDoneCallback        callback,
                                                           gpointer                                 data,
                                                           GDestroyNotify                           destroy_data);
GnomeKeyringResult gnome_keyring_lock_all_sync            (void);


/* NULL password means ask user */
gpointer           gnome_keyring_create             (const char                                   *keyring_name,
                                                     const char                                   *password,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_create_sync        (const char                                   *keyring_name,
                                                     const char                                   *password);
gpointer           gnome_keyring_unlock             (const char                                   *keyring,
                                                     const char                                   *password,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_unlock_sync        (const char                                   *keyring,
                                                     const char                                   *password);
gpointer           gnome_keyring_lock               (const char                                   *keyring,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_lock_sync          (const char                                   *keyring);
gpointer           gnome_keyring_delete             (const char                                   *keyring,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_delete_sync        (const char                                   *keyring);
gpointer           gnome_keyring_change_password             (const char                                   *keyring,
                                                     const char                                   *original,
                                                     const char                                   *password,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_change_password_sync        (const char                                   *keyring,
                                                         const char                                                        	   *original,
                                                     const char                                   *password);
gpointer           gnome_keyring_get_info           (const char                                   *keyring,
                                                     GnomeKeyringOperationGetKeyringInfoCallback   callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_get_info_sync      (const char                                   *keyring,
                                                     GnomeKeyringInfo                            **info);
gpointer           gnome_keyring_set_info           (const char                                   *keyring,
                                                     GnomeKeyringInfo                             *info,
                                                     GnomeKeyringOperationDoneCallback             callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_set_info_sync      (const char                                   *keyring,
                                                     GnomeKeyringInfo                             *info);
gpointer           gnome_keyring_list_item_ids      (const char                                   *keyring,
                                                     GnomeKeyringOperationGetListCallback          callback,
                                                     gpointer                                      data,
                                                     GDestroyNotify                                destroy_data);
GnomeKeyringResult gnome_keyring_list_item_ids_sync (const char                                   *keyring,
                                                     GList                                       **ids);

void              gnome_keyring_info_free             (GnomeKeyringInfo *keyring_info);
GnomeKeyringInfo *gnome_keyring_info_copy             (GnomeKeyringInfo *keyring_info);
GType             gnome_keyring_info_get_type         (void) G_GNUC_CONST;
void              gnome_keyring_info_set_lock_on_idle (GnomeKeyringInfo *keyring_info,
                                                       gboolean          value);
gboolean          gnome_keyring_info_get_lock_on_idle (GnomeKeyringInfo *keyring_info);
void              gnome_keyring_info_set_lock_timeout (GnomeKeyringInfo *keyring_info,
                                                       guint32           value);
guint32           gnome_keyring_info_get_lock_timeout (GnomeKeyringInfo *keyring_info);
time_t            gnome_keyring_info_get_mtime        (GnomeKeyringInfo *keyring_info);
time_t            gnome_keyring_info_get_ctime        (GnomeKeyringInfo *keyring_info);
gboolean          gnome_keyring_info_get_is_locked    (GnomeKeyringInfo *keyring_info);

#define GNOME_KEYRING_TYPE_INFO (gnome_keyring_info_get_type ())

gpointer gnome_keyring_find_items  (GnomeKeyringItemType                  type,
                                    GnomeKeyringAttributeList            *attributes,
                                    GnomeKeyringOperationGetListCallback  callback,
                                    gpointer                              data,
                                    GDestroyNotify                        destroy_data);
gpointer gnome_keyring_find_itemsv (GnomeKeyringItemType                  type,
                                    GnomeKeyringOperationGetListCallback  callback,
                                    gpointer                              data,
                                    GDestroyNotify                        destroy_data,
                                    ...);

GnomeKeyringResult gnome_keyring_find_items_sync  (GnomeKeyringItemType        type,
                                                   GnomeKeyringAttributeList  *attributes,
                                                   GList                     **found);
GnomeKeyringResult gnome_keyring_find_itemsv_sync (GnomeKeyringItemType        type,
                                                   GList                     **found,
                                                   ...);

gpointer           gnome_keyring_item_create              (const char                                 *keyring,
                                                           GnomeKeyringItemType                        type,
                                                           const char                                 *display_name,
                                                           GnomeKeyringAttributeList                  *attributes,
                                                           const char                                 *secret,
                                                           gboolean                                    update_if_exists,
                                                           GnomeKeyringOperationGetIntCallback         callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_create_sync         (const char                                 *keyring,
                                                           GnomeKeyringItemType                        type,
                                                           const char                                 *display_name,
                                                           GnomeKeyringAttributeList                  *attributes,
                                                           const char                                 *secret,
                                                           gboolean                                    update_if_exists,
                                                           guint32                                    *item_id);
gpointer           gnome_keyring_item_delete              (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringOperationDoneCallback           callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_delete_sync         (const char                                 *keyring,
                                                           guint32                                     id);
gpointer           gnome_keyring_item_get_info            (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringOperationGetItemInfoCallback    callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_get_info_sync       (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringItemInfo                      **info);
gpointer           gnome_keyring_item_get_info_full       (const char                                 *keyring,
                                                           guint32                                     id,
                                                           guint32                                     flags,
                                                           GnomeKeyringOperationGetItemInfoCallback    callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_get_info_full_sync  (const char                                 *keyring,
                                                           guint32                                     id,
                                                           guint32                                     flags,
                                                            GnomeKeyringItemInfo                      **info);
gpointer           gnome_keyring_item_set_info            (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringItemInfo                       *info,
                                                           GnomeKeyringOperationDoneCallback           callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_set_info_sync       (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringItemInfo                       *info);
gpointer           gnome_keyring_item_get_attributes      (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringOperationGetAttributesCallback  callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_get_attributes_sync (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringAttributeList                 **attributes);
gpointer           gnome_keyring_item_set_attributes      (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringAttributeList                  *attributes,
                                                           GnomeKeyringOperationDoneCallback           callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_set_attributes_sync (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringAttributeList                  *attributes);

void                  gnome_keyring_item_info_free             (GnomeKeyringItemInfo *item_info);
GnomeKeyringItemInfo *gnome_keyring_item_info_new              (void);
GnomeKeyringItemInfo *gnome_keyring_item_info_copy             (GnomeKeyringItemInfo *item_info);
GType                 gnome_keyring_item_info_get_gtype        (void) G_GNUC_CONST;
GnomeKeyringItemType  gnome_keyring_item_info_get_type         (GnomeKeyringItemInfo *item_info);
void                  gnome_keyring_item_info_set_type         (GnomeKeyringItemInfo *item_info,
                                                                GnomeKeyringItemType  type);
char *                gnome_keyring_item_info_get_secret       (GnomeKeyringItemInfo *item_info);
void                  gnome_keyring_item_info_set_secret       (GnomeKeyringItemInfo *item_info,
                                                                const char           *value);
char *                gnome_keyring_item_info_get_display_name (GnomeKeyringItemInfo *item_info);
void                  gnome_keyring_item_info_set_display_name (GnomeKeyringItemInfo *item_info,
                                                                const char           *value);
time_t                gnome_keyring_item_info_get_mtime        (GnomeKeyringItemInfo *item_info);
time_t                gnome_keyring_item_info_get_ctime        (GnomeKeyringItemInfo *item_info);

#define GNOME_KEYRING_TYPE_ITEM_INFO (gnome_keyring_item_info_get_gtype ())

/* ------------------------------------------------------------------------------
 * A Simpler API
 */

/*
 * This structure exists to help languages which have difficulty with
 * anonymous structures and is the same as the anonymous struct which
 * is defined in GnomeKeyringPasswordSchema, but it cannot be used
 * directly in GnomeKeyringPasswordSchema for API compatibility
 * reasons.
 */
typedef struct {
	const gchar* name;
	GnomeKeyringAttributeType type;
} GnomeKeyringPasswordSchemaAttribute;

typedef struct {
	GnomeKeyringItemType item_type;
	struct {
		const gchar* name;
		GnomeKeyringAttributeType type;
	} attributes[32];

	/* <private> */
	gpointer reserved1;
	gpointer reserved2;
	gpointer reserved3;
} GnomeKeyringPasswordSchema;

extern const GnomeKeyringPasswordSchema* GNOME_KEYRING_NETWORK_PASSWORD;

gpointer                 gnome_keyring_store_password         (const GnomeKeyringPasswordSchema* schema,
                                                               const gchar *keyring,
                                                               const gchar *display_name,
                                                               const gchar *password,
                                                               GnomeKeyringOperationDoneCallback callback,
                                                               gpointer data,
                                                               GDestroyNotify destroy_data,
                                                               ...) G_GNUC_NULL_TERMINATED;

GnomeKeyringResult       gnome_keyring_store_password_sync    (const GnomeKeyringPasswordSchema* schema,
                                                               const gchar *keyring,
                                                               const gchar *display_name,
                                                               const gchar *password,
                                                               ...) G_GNUC_NULL_TERMINATED;

gpointer                 gnome_keyring_find_password          (const GnomeKeyringPasswordSchema* schema,
                                                               GnomeKeyringOperationGetStringCallback callback,
                                                               gpointer data,
                                                               GDestroyNotify destroy_data,
                                                               ...) G_GNUC_NULL_TERMINATED;

GnomeKeyringResult       gnome_keyring_find_password_sync     (const GnomeKeyringPasswordSchema* schema,
                                                               gchar **password,
                                                               ...) G_GNUC_NULL_TERMINATED;

gpointer                 gnome_keyring_delete_password        (const GnomeKeyringPasswordSchema* schema,
                                                               GnomeKeyringOperationDoneCallback callback,
                                                               gpointer data,
                                                               GDestroyNotify destroy_data,
                                                               ...) G_GNUC_NULL_TERMINATED;

GnomeKeyringResult       gnome_keyring_delete_password_sync   (const GnomeKeyringPasswordSchema* schema,
                                                               ...) G_GNUC_NULL_TERMINATED;

void                     gnome_keyring_free_password          (gchar *password);

/* ------------------------------------------------------------------------------
 * Special Helpers for network password items
 */

typedef struct {
	char *keyring;
	guint32 item_id;

	char *protocol;
	char *server;
	char *object;
	char *authtype;
	guint32 port;

	char *user;
	char *domain;
	char *password;
} GnomeKeyringNetworkPasswordData;

void gnome_keyring_network_password_free (GnomeKeyringNetworkPasswordData *data);
void gnome_keyring_network_password_list_free (GList *list);

gpointer           gnome_keyring_find_network_password      (const char                            *user,
                                                             const char                            *domain,
                                                             const char                            *server,
                                                             const char                            *object,
                                                             const char                            *protocol,
                                                             const char                            *authtype,
                                                             guint32                                port,
                                                             GnomeKeyringOperationGetListCallback   callback,
                                                             gpointer                               data,
                                                             GDestroyNotify                         destroy_data);
GnomeKeyringResult gnome_keyring_find_network_password_sync (const char                            *user,
                                                             const char                            *domain,
                                                             const char                            *server,
                                                             const char                            *object,
                                                             const char                            *protocol,
                                                             const char                            *authtype,
                                                             guint32                                port,
                                                             GList                                **results);
gpointer           gnome_keyring_set_network_password       (const char                            *keyring,
                                                             const char                            *user,
                                                             const char                            *domain,
                                                             const char                            *server,
                                                             const char                            *object,
                                                             const char                            *protocol,
                                                             const char                            *authtype,
                                                             guint32                                port,
                                                             const char                            *password,
                                                             GnomeKeyringOperationGetIntCallback    callback,
                                                             gpointer                               data,
                                                             GDestroyNotify                         destroy_data);
GnomeKeyringResult gnome_keyring_set_network_password_sync  (const char                            *keyring,
                                                             const char                            *user,
                                                             const char                            *domain,
                                                             const char                            *server,
                                                             const char                            *object,
                                                             const char                            *protocol,
                                                             const char                            *authtype,
                                                             guint32                                port,
                                                             const char                            *password,
                                                             guint32                               *item_id);

/* -----------------------------------------------------------------------------
 * DEPRECATED STUFF
 */

#ifndef GNOME_KEYRING_DISABLE_DEPRECATED

typedef enum {
	GNOME_KEYRING_ACCESS_ASK,
	GNOME_KEYRING_ACCESS_DENY,
	GNOME_KEYRING_ACCESS_ALLOW
} GnomeKeyringAccessRestriction;

typedef struct GnomeKeyringAccessControl GnomeKeyringAccessControl;
typedef struct GnomeKeyringApplicationRef GnomeKeyringApplicationRef;

typedef enum {
	GNOME_KEYRING_ACCESS_READ = 1<<0,
	GNOME_KEYRING_ACCESS_WRITE = 1<<1,
	GNOME_KEYRING_ACCESS_REMOVE = 1<<2
} GnomeKeyringAccessType;

GnomeKeyringResult gnome_keyring_daemon_set_display_sync       (const char *display);

GnomeKeyringResult gnome_keyring_daemon_prepare_environment_sync (void);

gpointer           gnome_keyring_item_grant_access_rights      (const gchar                       *keyring,
                                                                const gchar                       *display_name,
                                                                const gchar                       *full_path,
                                                                const guint32                      id,
                                                                const GnomeKeyringAccessType       rights,
                                                                GnomeKeyringOperationDoneCallback  callback,
                                                                gpointer                           data,
                                                                GDestroyNotify                     destroy_data);

GnomeKeyringResult gnome_keyring_item_grant_access_rights_sync (const char                   *keyring,
                                                                const char                   *display_name,
                                                                const char                   *full_path,
                                                                const guint32                id,
                                                                const GnomeKeyringAccessType rights);


GnomeKeyringApplicationRef * gnome_keyring_application_ref_new          (void);
GnomeKeyringApplicationRef * gnome_keyring_application_ref_copy         (const GnomeKeyringApplicationRef *app);
void                         gnome_keyring_application_ref_free         (GnomeKeyringApplicationRef       *app);
GType                        gnome_keyring_application_ref_get_type     (void) G_GNUC_CONST;

#define GNOME_KEYRING_TYPE_APPLICATION_REF (gnome_keyring_application_ref_get_type ())

GnomeKeyringAccessControl *  gnome_keyring_access_control_new  (const GnomeKeyringApplicationRef *application,
                                                                GnomeKeyringAccessType            types_allowed);
GnomeKeyringAccessControl *  gnome_keyring_access_control_copy (GnomeKeyringAccessControl        *ac);
GType                        gnome_keyring_access_control_get_type (void) G_GNUC_CONST;
void                         gnome_keyring_access_control_free (GnomeKeyringAccessControl *ac);

#define GNOME_KEYRING_TYPE_ACCESS_CONTROL (gnome_keyring_access_control_get_type ())

GList * gnome_keyring_acl_copy            (GList                     *list);
void    gnome_keyring_acl_free            (GList                     *acl);


char *                gnome_keyring_item_ac_get_display_name   (GnomeKeyringAccessControl *ac);
void                  gnome_keyring_item_ac_set_display_name   (GnomeKeyringAccessControl *ac,
                                                                const char           *value);

char *                gnome_keyring_item_ac_get_path_name      (GnomeKeyringAccessControl *ac);
void                  gnome_keyring_item_ac_set_path_name      (GnomeKeyringAccessControl *ac,
                                                                const char           *value);


GnomeKeyringAccessType gnome_keyring_item_ac_get_access_type   (GnomeKeyringAccessControl *ac);
void                   gnome_keyring_item_ac_set_access_type   (GnomeKeyringAccessControl *ac,
                                                                const GnomeKeyringAccessType value);

gpointer           gnome_keyring_item_get_acl             (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GnomeKeyringOperationGetListCallback        callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_get_acl_sync        (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GList                                     **acl);
gpointer           gnome_keyring_item_set_acl             (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GList                                      *acl,
                                                           GnomeKeyringOperationDoneCallback           callback,
                                                           gpointer                                    data,
                                                           GDestroyNotify                              destroy_data);
GnomeKeyringResult gnome_keyring_item_set_acl_sync        (const char                                 *keyring,
                                                           guint32                                     id,
                                                           GList                                      *acl);

#endif /* GNOME_KEYRING_DISABLE_DEPRECATED */

G_END_DECLS

#endif /* GNOME_KEYRING_H */
